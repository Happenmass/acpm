package dev.androidagent.sample.androiduse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityNodeInfo.RangeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

internal data class AndroidUseTool(
    val name: String,
    val description: String,
    val schema: JsonObject,
)

private fun tool(name: String, description: String, vararg properties: Pair<String, String>, required: List<String> = emptyList()) =
    AndroidUseTool("android_$name", description, buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            for ((key, type) in properties) putJsonObject(key) { put("type", type) }
        }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    })

/** A turn-scoped handle. The accessibility service installs and revokes it on the main thread. */
internal class AndroidUseSession private constructor(
    private val service: AndroidUseService,
    val targetPackage: String,
    private val handoffs: Boolean,
) : Closeable {
    private data class Window(val id: Int, val layer: Int, val bounds: Rect, val rotation: Int)
    private data class Destination(val packageName: String, val windowId: Int)
    private data class RangeValue(val type: String, val min: Float, val max: Float, val current: Float, val adjustable: Boolean)
    private data class Node(
        val id: Int,
        val path: List<Int>,
        val bounds: Rect,
        val signature: List<String?>,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val tapTargetId: Int?,
        val enabled: Boolean,
        val checked: Boolean?,
        val selected: Boolean?,
        val focused: Boolean,
        val scrollDirections: List<String>,
        val range: RangeValue?,
    )
    private data class Snapshot(
        val id: String,
        val window: Window,
        val revision: Long,
        val windowRevision: Long,
        val nodes: List<Node>,
        val frame: Pixels,
    )
    private data class Pixels(val width: Int, val height: Int, val colors: IntArray) {
        fun matchesAround(other: Pixels, x: Int, y: Int): Boolean {
            if (width != other.width || height != other.height) return false
            val radius = 24
            val left = maxOf(0, x - radius)
            val right = minOf(width, x + radius + 1)
            for (row in maxOf(0, y - radius) until minOf(height, y + radius + 1)) {
                for (index in (row * width + left) until (row * width + right)) {
                    if (colors[index] != other.colors[index]) return false
                }
            }
            return true
        }
    }
    private data class Frame(val pixels: Pixels, val jpeg: String?)
    private data class Observation(val body: JsonObject, val jpeg: String?)
    private class ToolError(val code: String, override val message: String) : Exception(message)

    private var snapshot: Snapshot? = null
    private var destination: Destination? = null
    private var pendingDecision: CompletableDeferred<Boolean>? = null
    private val deniedPackages = mutableSetOf<String>()
    val isGranted get() = service.grant === this

    override fun close() {
        if (service.grant === this) service.grant = null
        snapshot = null
        destination = null
        pendingDecision?.complete(false)
    }

    suspend fun call(tool: String, args: JsonObject): JsonObject = withContext(Dispatchers.Main.immediate) {
        try {
            requireGrant()
            if (pendingDecision != null) fail("authorization_pending", "Wait for the user to decide in AI SDK.")
            if (tool != "android_open_app" && tool in toolNames) authorizeForeground()
            when (tool) {
                "android_open_app" -> openApp()
                "android_observe" -> observe(args["screenshot"]?.jsonPrimitive?.booleanOrNull ?: false)
                "android_tap" -> tap(args)
                "android_type" -> type(args)
                "android_scroll" -> scroll(args)
                "android_set_progress" -> setProgress(args)
                "android_swipe" -> swipe(args)
                "android_back" -> {
                    actionTarget(args)
                    accepted(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                }
                else -> fail("unknown_tool", "Unknown Android Use tool.")
            }
        } catch (error: ToolError) {
            response(buildJsonObject { put("code", error.code); put("message", error.message) }, error = true)
        } catch (_: IllegalArgumentException) {
            response(buildJsonObject { put("code", "invalid_argument"); put("message", "Invalid Android Use arguments.") }, error = true)
        } catch (_: IllegalStateException) {
            response(buildJsonObject { put("code", "unavailable"); put("message", "Android Use is unavailable for this window.") }, error = true)
        }
    }

    private fun requireGrant() {
        if (!isGranted || AndroidUseService.connected !== service) fail("revoked", "Android Use access was revoked.")
        if (service.getSystemService(PowerManager::class.java)?.isInteractive != true ||
            service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) {
            close()
            fail("revoked", "Android Use access ended when the device locked.")
        }
    }

    internal fun includesPackage(packageName: String?) = packageName != null &&
        (packageName == targetPackage || packageName == destination?.packageName)

    internal fun onWindowChanged() {
        val granted = destination ?: return
        val active = service.rootInActiveWindow?.let(::identity) ?: return
        if (active != granted && windowInfo(active.windowId)?.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
            destination = null
            snapshot = null
        }
    }

    private fun identity(root: AccessibilityNodeInfo): Destination? = root.packageName?.toString()?.let {
        Destination(it, root.windowId)
    }

    private suspend fun authorizeForeground() {
        val active = service.rootInActiveWindow?.let(::identity) ?: return
        if (active.packageName == targetPackage || active == destination) return
        snapshot = null
        destination = null
        if (!handoffs || active.packageName == service.packageName) return
        if (active.packageName in deniedPackages) fail("authorization_denied", "The user declined this destination for this task.")
        val window = windowInfo(active.windowId) ?: return
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION && window.type != AccessibilityWindowInfo.TYPE_SYSTEM) return

        val decision = CompletableDeferred<Boolean>()
        val prompt = AndroidUseConsent.show(service, active.packageName, window.title?.toString()?.take(100)) {
            decision.complete(it)
        }
        pendingDecision = decision
        try {
            val approved = prompt.use { withTimeoutOrNull(30_000) { decision.await() } }
            requireGrant()
            if (approved == null) fail("authorization_expired", "The user did not authorize the destination in time.")
            if (!approved) {
                deniedPackages.add(active.packageName)
                fail("authorization_denied", "The user declined this destination for this task.")
            }
            // Removing the prompt restores the foreground window before the grant is checked.
            delay(150)
            requireGrant()
            if (service.rootInActiveWindow?.let(::identity) != active || windowInfo(active.windowId) == null) {
                fail("authorization_expired", "The destination window changed before authorization could take effect.")
            }
            destination = active
        } finally {
            pendingDecision = null
        }
    }

    private fun activeRoot(): AccessibilityNodeInfo {
        requireGrant()
        val root = service.rootInActiveWindow
        val active = root?.let(::identity)
        if (root == null || active == null || (active.packageName != targetPackage && active != destination)) {
            snapshot = null
            fail("wrong_app", "The authorized app is not in the foreground. Open it or return to it.")
        }
        return root
    }

    private fun windowInfo(id: Int) = service.windows.firstOrNull { it.id == id && it.displayId == Display.DEFAULT_DISPLAY }

    private fun windowFor(root: AccessibilityNodeInfo): Window {
        val window = windowInfo(root.windowId)
            ?: fail("no_window", "The foreground app window is unavailable.")
        val bounds = Rect().also(window::getBoundsInScreen)
        if (bounds.isEmpty) fail("no_window", "The foreground app window has no usable bounds.")
        val rotation = service.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        return Window(window.id, window.layer, bounds, rotation)
    }

    private suspend fun openApp(): JsonObject {
        snapshot = null
        destination = null
        if (service.rootInActiveWindow?.packageName?.toString() != targetPackage) {
            val launch = service.packageManager.getLaunchIntentForPackage(targetPackage)
                ?: fail("not_launchable", "This app has no launcher activity available.")
            try {
                service.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                fail("not_launchable", "The authorized app could not be opened.")
            } catch (_: SecurityException) {
                fail("not_launchable", "Android did not allow the authorized app to open.")
            }
            withTimeoutOrNull(2_000) {
                while (service.rootInActiveWindow?.packageName?.toString() != targetPackage) {
                    requireGrant()
                    delay(50)
                }
            }
        }
        val foreground = service.rootInActiveWindow?.packageName?.toString() == targetPackage
        val result = buildJsonObject {
            put("package", targetPackage)
            put("inForeground", foreground)
            if (!foreground) put("next", "Bring the authorized app to the foreground.")
        }
        return if (foreground) afterAction(result) else response(result)
    }

    private suspend fun observe(includeImage: Boolean): JsonObject = captureObservation(includeImage).let {
        response(it.body, it.jpeg)
    }

    private suspend fun captureObservation(includeImage: Boolean): Observation {
        snapshot = null
        val (root, window, frame) = captureActiveWindow(includeImage)
        val nodes = collectNodes(root)
        val state = Snapshot(UUID.randomUUID().toString(), window, service.revision, service.windowRevision,
            nodes, frame.pixels)
        snapshot = state
        return Observation(buildJsonObject {
            put("package", root.packageName.toString())
            put("snapshotId", state.id)
            put("screenshotWidth", state.frame.width)
            put("screenshotHeight", state.frame.height)
            putJsonArray("elements") {
                for (node in nodes) addJsonObject {
                    put("id", node.id)
                    node.signature[0]?.let { put("text", it) }
                    node.signature[1]?.let { put("description", it) }
                    node.signature[2]?.let { put("resourceId", it) }
                    node.signature[3]?.let { put("class", it.substringAfterLast('.')) }
                    if (node.clickable) put("clickable", true)
                    if (node.editable) put("editable", true)
                    if (node.scrollable) put("scrollable", true)
                    node.tapTargetId?.let { put("tapTargetId", it) }
                    if (!node.enabled) put("enabled", false)
                    node.checked?.let { put("checked", it) }
                    node.selected?.let { put("selected", it) }
                    if (node.focused) put("focused", true)
                    if (node.scrollDirections.isNotEmpty()) putJsonArray("scrollDirections") {
                        node.scrollDirections.forEach { add(it) }
                    }
                    node.range?.let { range ->
                        putJsonObject("range") {
                            put("type", range.type)
                            put("min", range.min)
                            put("max", range.max)
                            put("current", range.current)
                            put("adjustable", range.adjustable)
                        }
                    }
                    putJsonArray("bounds") {
                        add(scaleToImage(node.bounds.left, window.bounds.left, window.bounds.width(), state.frame.width))
                        add(scaleToImage(node.bounds.top, window.bounds.top, window.bounds.height(), state.frame.height))
                        add(scaleToImage(node.bounds.right, window.bounds.left, window.bounds.width(), state.frame.width))
                        add(scaleToImage(node.bounds.bottom, window.bounds.top, window.bounds.height(), state.frame.height))
                    }
                }
            }
        }, frame.jpeg)
    }

    private suspend fun captureActiveWindow(includeImage: Boolean): Triple<AccessibilityNodeInfo, Window, Frame> {
        repeat(2) { attempt ->
            try {
                val window = windowFor(activeRoot())
                val windowRevision = service.windowRevision
                // Protected windows return no data, including for a tree-only observation.
                val frame = capture(window.id, includeImage)
                val root = activeRoot()
                if (window == windowFor(root) && windowRevision == service.windowRevision) {
                    return Triple(root, window, frame)
                }
            } catch (error: ToolError) {
                if (attempt != 0 || error.code != "no_window") throw error
            }
            if (attempt == 0) delay(150)
        }
        fail("stale_snapshot", "The window changed during observation. Observe again.")
    }

    private fun scaleToImage(value: Int, origin: Int, extent: Int, imageExtent: Int): Int =
        ((value - origin).toDouble() * imageExtent / extent).roundToInt()

    private fun collectNodes(root: AccessibilityNodeInfo): List<Node> {
        val result = mutableListOf<Node>()
        var remaining = 450
        fun visit(node: AccessibilityNodeInfo, path: List<Int>, parentTarget: Node?) {
            if (remaining-- <= 0 || path.size > 28 || result.size >= 100 || node.isPassword ||
                node.windowId != root.windowId || node.packageName?.toString() != root.packageName?.toString()) return
            val signature = signature(node)
            val isLabel = signature[0] != null || signature[1] != null
            val range = readRange(node)
            var childTarget = parentTarget
            val isContainer = path.isEmpty() || node.isScrollable || node.collectionInfo != null
            if (isContainer || node.isClickable || node.isEditable) childTarget = null
            if (node.isVisibleToUser && (isLabel || node.isClickable || node.isEditable || node.isScrollable ||
                    node.isCheckable || node.isSelected || range != null)) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (!bounds.isEmpty) {
                    val tapTarget = childTarget?.takeIf { isLabel && node.isEnabled && it.bounds.contains(bounds) }
                    val item = node.collectionItemInfo
                    val selected = when {
                        node.isSelected || item?.isSelected == true -> true
                        item != null || node.supports(AccessibilityAction.ACTION_SELECT) -> false
                        else -> null
                    }
                    val observed = Node(result.size + 1, path, bounds, signature, node.isClickable,
                        node.isEditable, node.isScrollable, tapTarget?.id, node.isEnabled,
                        node.isChecked.takeIf { node.isCheckable }, selected, node.isFocused,
                        scrollDirections(node), range)
                    result.add(observed)
                    if (!isContainer && isClickTarget(node)) childTarget = observed
                }
            }
            for (index in 0 until node.childCount) {
                if (remaining <= 0 || result.size >= 100) break
                node.getChild(index)?.let { visit(it, path + index, childTarget) }
            }
        }
        visit(root, emptyList(), null)
        return result
    }

    private fun signature(node: AccessibilityNodeInfo) = listOf(
        node.text?.toString()?.take(200), node.contentDescription?.toString()?.take(200),
        node.viewIdResourceName, node.className?.toString(),
    )

    private fun isClickTarget(node: AccessibilityNodeInfo) = node.isClickable && node.isEnabled && !node.isEditable &&
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    private fun AccessibilityNodeInfo.supports(action: AccessibilityAction) = actionList.any { it.id == action.id }

    private fun scrollDirections(node: AccessibilityNodeInfo): List<String> =
        if (node.isScrollable && node.isEnabled) scrollActions.filterValues { node.supports(it) }.keys.toList() else emptyList()

    private fun readRange(node: AccessibilityNodeInfo): RangeValue? {
        val range = node.rangeInfo ?: return null
        val type = when (range.type) {
            RangeInfo.RANGE_TYPE_INT -> "integer"
            RangeInfo.RANGE_TYPE_FLOAT -> "float"
            RangeInfo.RANGE_TYPE_PERCENT -> "percent"
            else -> return null
        }
        if (!range.min.isFinite() || !range.max.isFinite() || range.current !in range.min..range.max) return null
        return RangeValue(type, range.min, range.max, range.current,
            node.isEnabled && node.supports(AccessibilityAction.ACTION_SET_PROGRESS))
    }

    private suspend fun capture(windowId: Int, includeImage: Boolean): Frame {
        var attempts = 0
        while (true) {
            requireGrant()
            val image = try {
                withTimeoutOrNull(3_000) {
                    suspendCancellableCoroutine<Bitmap> { continuation ->
                        service.takeScreenshotOfWindow(windowId, service.mainExecutor,
                            object : AccessibilityService.TakeScreenshotCallback {
                                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                    result.hardwareBuffer.use { buffer ->
                                        if (!continuation.isActive) return
                                        val image = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                        if (image == null) {
                                            continuation.resumeWith(Result.failure(ToolError("capture_unavailable", "Android could not read this window.")))
                                            return
                                        }
                                        // A software copy owns its storage after the screenshot buffer is released.
                                        val copy = try { image.copy(Bitmap.Config.ARGB_8888, false) } finally { image.recycle() }
                                        if (copy == null) continuation.resumeWith(Result.failure(ToolError("capture_unavailable", "Android could not copy the screenshot.")))
                                        else continuation.resume(copy) { _, value, _ -> value.recycle() }
                                    }
                                }

                                override fun onFailure(errorCode: Int) {
                                    if (!continuation.isActive) return
                                    val failure = when (errorCode) {
                                        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                                            ToolError("protected_window", "Android blocked capture of this protected window.")
                                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                                            ToolError("capture_busy", "Android is limiting screenshot frequency.")
                                        else -> ToolError("capture_unavailable", "Android could not capture the window ($errorCode).")
                                    }
                                    continuation.resumeWith(Result.failure(failure))
                                }
                            })
                    }
                } ?: fail("capture_unavailable", "Screen capture timed out.")
            } catch (error: ToolError) {
                if (error.code != "capture_busy" || attempts++ > 0) throw error
                delay(400)
                continue
            }
            try {
                return withContext(Dispatchers.Default) { frame(image, includeImage) }
            } finally {
                image.recycle()
            }
        }
    }

    private fun frame(image: Bitmap, includeImage: Boolean): Frame {
        val ratio = minOf(1.0, 1280.0 / maxOf(image.width, image.height))
        val width = (image.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (image.height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(image, width, height, true)
        try {
            val colors = IntArray(width * height)
            scaled.getPixels(colors, 0, width, 0, 0, width, height)
            val jpeg = if (includeImage) {
                val output = ByteArrayOutputStream()
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, output))
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            } else null
            return Frame(Pixels(width, height, colors), jpeg)
        } finally {
            if (scaled !== image) scaled.recycle()
        }
    }

    private fun actionTarget(args: JsonObject, consume: Boolean = true): Pair<AccessibilityNodeInfo, Snapshot> {
        val root = activeRoot()
        val state = snapshot
        if (state == null || state.id != args["snapshotId"]?.jsonPrimitive?.contentOrNull) {
            fail("stale_snapshot", "Call android_observe again before acting.")
        }
        requireCurrentWindow(root, state)
        if (consume) snapshot = null
        return root to state
    }

    private fun requireCurrentWindow(root: AccessibilityNodeInfo, state: Snapshot) {
        if (state.windowRevision != service.windowRevision || state.window != windowFor(root)) {
            snapshot = null
            fail("stale_snapshot", "The window changed. Call android_observe again before acting.")
        }
    }

    private fun resolve(root: AccessibilityNodeInfo, state: Snapshot, id: Int): AccessibilityNodeInfo {
        val node = state.nodes.firstOrNull { it.id == id }
            ?: fail("stale_snapshot", "The element is not in this snapshot.")
        var current = root
        for (index in node.path) current = current.getChild(index)
            ?: fail("stale_snapshot", "The element moved. Observe again.")
        if (!current.isVisibleToUser || current.isPassword || !current.isEnabled ||
            current.windowId != root.windowId || current.packageName?.toString() != root.packageName?.toString() ||
            signature(current) != node.signature || Rect().also(current::getBoundsInScreen) != node.bounds) {
            fail("stale_snapshot", "The element changed. Observe again.")
        }
        return current
    }

    private suspend fun tap(args: JsonObject): JsonObject {
        val (root, state) = actionTarget(args, consume = false)
        val nodeId = args["id"]?.jsonPrimitive?.intOrNull
        if (nodeId != null) {
            require("x" !in args && "y" !in args)
            val node = resolve(root, state, nodeId)
            val observed = state.nodes.first { it.id == nodeId }
            val clicked = when {
                node.isClickable -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.isEditable && node.isFocusable -> node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                observed.tapTargetId != null -> {
                    val target = resolve(root, state, observed.tapTargetId)
                    if (!isClickTarget(target) || target.isScrollable || target.collectionInfo != null) {
                        fail("stale_snapshot", "The element's tap target changed. Observe again.")
                    }
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                else -> fail("unsupported_action", "This element does not expose click or focus. Coordinates may work with this snapshot.")
            }
            return elementAction(clicked)
        }
        val imageX = requireNotNull(args["x"]?.jsonPrimitive?.intOrNull)
        val imageY = requireNotNull(args["y"]?.jsonPrimitive?.intOrNull)
        val (x, y) = point(state, imageX, imageY)
        val area = Rect(x, y, x + 1, y + 1)
        checkCoverage(state, area)
        val current = capture(state.window.id, includeImage = false).pixels
        // Capture suspends. Recheck the grant, snapshot and topmost window before dispatch.
        actionTarget(args, consume = false)
        checkCoverage(state, area)
        if (!state.frame.matchesAround(current, imageX, imageY)) {
            fail("stale_snapshot", "The tap area changed. Observe again or try a different target.")
        }
        return accepted(gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 60) { snapshot = null })
    }

    private suspend fun type(args: JsonObject): JsonObject {
        val (root, state) = actionTarget(args)
        val id = requireNotNull(args["id"]?.jsonPrimitive?.intOrNull)
        val text = requireNotNull(args["text"]?.jsonPrimitive?.contentOrNull)
        require(text.length <= 4_000)
        val node = resolve(root, state, id)
        if (!node.isEditable || node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
            fail("unsupported_action", "This element does not expose native text input.")
        }
        return accepted(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
    }

    private suspend fun scroll(args: JsonObject): JsonObject {
        val (root, state) = actionTarget(args, consume = false)
        val id = requireNotNull(args["id"]?.jsonPrimitive?.intOrNull)
        val direction = requireNotNull(args["direction"]?.jsonPrimitive?.contentOrNull)
        val action = requireNotNull(scrollActions[direction])
        val node = resolve(root, state, id)
        if (direction !in state.nodes.first { it.id == id }.scrollDirections) {
            fail("unsupported_action", "Use a direction listed in this element's scrollDirections.")
        }
        if (!node.isScrollable || !node.supports(action)) {
            fail("stale_snapshot", "The element can no longer scroll in this direction. Observe again.")
        }
        val amount = args["amount"]?.jsonPrimitive?.let { requireNotNull(it.floatOrNull) }
        val options = amount?.let {
            require(it.isFinite() && it > 0 && it <= 1)
            if (Build.VERSION.SDK_INT < 35) fail("unsupported_action", "Scroll amount requires Android 15 or later.")
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT, it) }
        }
        return elementAction(node.performAction(action.id, options))
    }

    private suspend fun setProgress(args: JsonObject): JsonObject {
        val (root, state) = actionTarget(args, consume = false)
        val id = requireNotNull(args["id"]?.jsonPrimitive?.intOrNull)
        val value = requireNotNull(args["value"]?.jsonPrimitive?.floatOrNull)
        val node = resolve(root, state, id)
        val observed = state.nodes.first { it.id == id }.range
        if (observed?.adjustable != true) fail("unsupported_action", "This element has no adjustable range.")
        val current = readRange(node)
        if (current?.adjustable != true || current.type != observed.type || current.min != observed.min || current.max != observed.max) {
            fail("stale_snapshot", "The element's range changed. Observe again.")
        }
        require(value.isFinite() && value in current.min..current.max && (current.type != "integer" || value % 1f == 0f))
        return elementAction(node.performAction(AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
        }))
    }

    private suspend fun elementAction(ok: Boolean): JsonObject {
        if (ok) snapshot = null
        return accepted(ok)
    }

    private suspend fun swipe(args: JsonObject): JsonObject {
        val (_, state) = actionTarget(args)
        val (x1, y1) = point(state, args, "x1", "y1")
        val (x2, y2) = point(state, args, "x2", "y2")
        require(x1 != x2 || y1 != y2)
        checkGesture(state, Rect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2) + 1, maxOf(y1, y2) + 1))
        return accepted(gesture(Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }, 330))
    }

    private fun point(state: Snapshot, args: JsonObject, xKey: String, yKey: String): Pair<Int, Int> {
        val x = requireNotNull(args[xKey]?.jsonPrimitive?.intOrNull)
        val y = requireNotNull(args[yKey]?.jsonPrimitive?.intOrNull)
        return point(state, x, y)
    }

    private fun point(state: Snapshot, x: Int, y: Int): Pair<Int, Int> {
        require(x in 0 until state.frame.width && y in 0 until state.frame.height)
        val bounds = state.window.bounds
        return bounds.left + (x.toLong() * bounds.width() / state.frame.width).toInt() to
            bounds.top + (y.toLong() * bounds.height() / state.frame.height).toInt()
    }

    private fun checkGesture(state: Snapshot, area: Rect) {
        if (state.revision != service.revision) fail("stale_snapshot", "The screen changed. Observe again.")
        checkCoverage(state, area)
    }

    private fun checkCoverage(state: Snapshot, area: Rect) {
        if (service.windows.any { window ->
                window.displayId == Display.DEFAULT_DISPLAY && window.layer > state.window.layer &&
                    Rect.intersects(area, Rect().also(window::getBoundsInScreen))
            }) fail("covered_target", "Another window covers this gesture. Observe again.")
    }

    private suspend fun gesture(path: Path, duration: Long, onDispatched: () -> Unit = {}): Boolean =
        withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { continuation ->
                val gesture = GestureDescription.Builder().setDisplayId(Display.DEFAULT_DISPLAY)
                    .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
                val scheduled = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) { continuation.resume(true) }
                    override fun onCancelled(gestureDescription: GestureDescription) { continuation.resume(false) }
                }, null)
                if (scheduled) onDispatched() else continuation.resume(false)
            }
        } ?: false

    private suspend fun accepted(ok: Boolean): JsonObject {
        if (!ok) fail("action_rejected", "Android did not confirm the action. Observe before trying again.")
        return afterAction(buildJsonObject { put("accepted", true) })
    }

    private suspend fun afterAction(result: JsonObject): JsonObject {
        // Let Android publish the resulting UI. Continuously changing content must not stall an action reply.
        withTimeoutOrNull(450) {
            do {
                val revision = service.revision
                delay(130)
            } while (revision != service.revision)
        }
        // This read uses the existing grant. A handoff still needs an explicit request and user consent.
        val observation = try {
            captureObservation(includeImage = false).body
        } catch (error: ToolError) {
            return observationUnavailable(result, error.code)
        } catch (_: IllegalStateException) {
            return observationUnavailable(result, "unavailable")
        }
        return response(buildJsonObject {
            result.forEach { (key, value) -> put(key, value) }
            put("observation", observation)
            put("next", "Use observation.snapshotId. Verify the actual requested values before reporting success.")
        })
    }

    private fun observationUnavailable(result: JsonObject, code: String) = response(buildJsonObject {
        result.forEach { (key, value) -> put(key, value) }
        put("observationError", code)
        put("next", "Call android_observe for the resulting state. Do not repeat an accepted action just to verify it.")
    })

    private fun fail(code: String, message: String): Nothing = throw ToolError(code, message)

    private fun response(body: JsonObject, jpeg: String? = null, error: Boolean = false) = buildJsonObject {
        if (error) put("isError", true)
        // Codex prefers structuredContent over content. Keep the metadata and image together
        // in the standard content array for visual observations.
        if (jpeg == null) put("structuredContent", body)
        putJsonArray("content") {
            addJsonObject { put("type", "text"); put("text", body.toString()) }
            if (jpeg != null) addJsonObject { put("type", "image"); put("mimeType", "image/jpeg"); put("data", jpeg) }
        }
    }

    companion object {
        private val scrollActions = mapOf(
            "forward" to AccessibilityAction.ACTION_SCROLL_FORWARD,
            "backward" to AccessibilityAction.ACTION_SCROLL_BACKWARD,
            "up" to AccessibilityAction.ACTION_SCROLL_UP,
            "down" to AccessibilityAction.ACTION_SCROLL_DOWN,
            "left" to AccessibilityAction.ACTION_SCROLL_LEFT,
            "right" to AccessibilityAction.ACTION_SCROLL_RIGHT,
        )
        val tools = listOf(
            tool("open_app", "Return to the Android app originally authorized for this task. Reuse the returned observation when available."),
            tool("observe", "Read the authorized foreground window and obtain its snapshot ID. Reuse an action's observation when it already shows the state. Set screenshot=true for an image. An explicit observation in a new window waits for the user's authorization.",
                "screenshot" to "boolean"),
            tool("tap", "Tap an element ID or screenshot coordinates. A label with tapTargetId can be tapped by its own ID. If the element has no tap action, retry with coordinates from the same snapshot. Use the returned observation after success.",
                "snapshotId" to "string", "id" to "integer", "x" to "integer", "y" to "integer", required = listOf("snapshotId")),
            tool("type", "Replace the text of an editable element using the latest snapshot. Supports Unicode.",
                "snapshotId" to "string", "id" to "integer", "text" to "string", required = listOf("snapshotId", "id", "text")),
            tool("scroll", "Scroll an element using its latest snapshot ID and one of its scrollDirections. Optional amount is a fraction of one visible page (greater than 0 and at most 1) on Android 15+. Use the returned observation for the new position.",
                "snapshotId" to "string", "id" to "integer", "direction" to "string", "amount" to "number",
                required = listOf("snapshotId", "id", "direction")),
            tool("set_progress", "Set a control whose range.adjustable is true to a value between range.min and range.max in native units. Use the latest snapshot ID. Check range.current in the returned observation.",
                "snapshotId" to "string", "id" to "integer", "value" to "number", required = listOf("snapshotId", "id", "value")),
            tool("swipe", "Swipe between two points in the latest screenshot's pixel coordinates.",
                "snapshotId" to "string", "x1" to "integer", "y1" to "integer", "x2" to "integer", "y2" to "integer",
                required = listOf("snapshotId", "x1", "y1", "x2", "y2")),
            tool("back", "Press Android Back while still in the authorized foreground app.",
                "snapshotId" to "string", required = listOf("snapshotId")),
        )
        val toolNames = tools.map(AndroidUseTool::name).toSet()

        fun start(context: Context, targetPackage: String, handoffs: Boolean = false): AndroidUseSession {
            require(Build.VERSION.SDK_INT >= 34) { "Android Use requires Android 14 or later." }
            val service = requireNotNull(AndroidUseService.connected) { "Enable AI SDK Android Use in Android Accessibility settings." }
            require(targetPackage != context.packageName && context.packageManager.getLaunchIntentForPackage(targetPackage) != null) {
                "Choose an installed app with a launcher activity."
            }
            require(service.grant == null) { "Another Android Use task is active." }
            return AndroidUseSession(service, targetPackage, handoffs).also { service.grant = it }
        }

        fun revoke() {
            AndroidUseService.connected?.grant?.close()
        }
    }
}
