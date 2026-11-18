package dev.androidagent.sample

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.androidagent.sample.androiduse.AndroidUseService
import dev.androidagent.sample.androiduse.AndroidUseSession
import dev.androidagent.sdk.AgentBackend
import dev.androidagent.sdk.AgentTurn
import dev.androidagent.sdk.ModelEndpoint
import dev.androidagent.sdk.RunStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

internal class AndroidUseProbe(private val test: Instrumentation) {
    private val automation = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val sdk get() = test.targetContext
    private val fixture get() = test.context.packageName

    suspend fun run(backend: AgentBackend, port: Int, model: String): String = withFixtureService {
        launchFixture()
        runTask(backend, port, model, fixture, """
            Use the Android Use MCP tools to operate the test app. Discover android_observe and android_type with tool_search if necessary.
            Call android_observe with screenshot=true. Read the large five-character code in the screenshot, which is not in the element text.
            Use android_type to put only the five characters into the editable Code entry element, using its numeric ID and the latest snapshotId.
            Call android_observe again to check for Code accepted; correct the entry if needed. Finish when the app reports acceptance.
            Use only Android Use tools for the app. Do not guess without looking at the screenshot.
        """.trimIndent()) { awaitText("Code accepted") }
        "ANDROID_USE_REAL_MODEL_PASS backend=$backend fixture=$fixture independently verified Code accepted"
    }

    suspend fun clockTimer(backend: AgentBackend, port: Int, model: String): String = withFixtureService {
        val clock = "com.google.android.deskclock"
        val (minutes, seconds) = if (backend == AgentBackend.Pi) 1 to 37 else 2 to 46
        check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        await("leave Clock before the run") { automation.rootInActiveWindow?.packageName?.toString()?.let { it != clock } == true }
        val elapsed = android.os.SystemClock.elapsedRealtime()
        runTask(backend, port, model, clock, """
            In Android Clock, prepare a Timer for $minutes minutes and $seconds seconds. Leave the digits entered in the setup screen.
            Do not start the timer. Use only the Android Use MCP for the app. Discover android_open_app, android_observe and android_tap with tool_search if necessary.
        """.trimIndent()) {
            await("Clock's unstarted $minutes:$seconds timer entry") {
                val root = automation.rootInActiveWindow ?: return@await false
                root.packageName?.toString() == clock &&
                    hasNode(root) { node ->
                        node.text?.toString() == "00h 0${minutes}m ${seconds}s" ||
                            node.contentDescription?.toString() == "0 hours, $minutes minutes, $seconds seconds"
                    } &&
                    !hasNode(root) { it.contentDescription?.toString() in setOf("Pause", "Resume") }
            }
        }
        "ANDROID_USE_CLOCK_TIMER_PASS backend=$backend draft=0${minutes}:$seconds elapsedMs=${android.os.SystemClock.elapsedRealtime() - elapsed}"
    }

    suspend fun realApp(
        backend: AgentBackend, port: Int, model: String, case: AndroidUseRealCase,
    ): String = withFixtureService {
        val notes = AndroidUseRealCase.Markor.target
        val contactsBefore = if (case == AndroidUseRealCase.Contacts) contactRows() else null
        if (case == AndroidUseRealCase.Receipt) {
            val grant = withContext(Dispatchers.Main) { AndroidUseSession.start(sdk, case.target) }
            try {
                launchApp(case.target)
                await("Gallery as the granted foreground app") {
                    automation.rootInActiveWindow?.packageName?.toString() == case.target
                }
                delay(350)
                val gallerySnapshot = structured(observe(grant, screenshot = true))
                launchApp(notes)
                await("Markor as the ungranted foreground app") {
                    automation.rootInActiveWindow?.packageName?.toString() == notes
                }
                check(error(observe(grant, screenshot = true)) == "wrong_app")
                val write = grant.call("android_type", buildJsonObject {
                    put("snapshotId", gallerySnapshot.getValue("snapshotId"))
                    put("id", 1)
                    put("text", "This write must be blocked.")
                })
                check(error(write) == "wrong_app")
                check(error(tap(grant, gallerySnapshot, "x" to 20, "y" to 20)) == "wrong_app")
            } finally {
                withContext(Dispatchers.Main) { grant.close() }
            }
        }
        val start = android.os.SystemClock.elapsedRealtime()
        val handoffTarget = when (case) {
            AndroidUseRealCase.HandoffBrightness -> "com.android.systemui"
            AndroidUseRealCase.HandoffSms -> "com.google.android.apps.messaging"
            AndroidUseRealCase.Receipt -> notes
            else -> null
        }
        val allow = case != AndroidUseRealCase.Receipt
        var decisions = 0
        val operator: (suspend () -> Unit)? = handoffTarget?.let { target ->
            suspend {
                do {
                    tapConsent(awaitConsent(target, allow, 100_000))
                    decisions++
                    delay(350)
                } while (case == AndroidUseRealCase.HandoffBrightness)
            }
        }
        val final = runTask(backend, port, model, case.target, case.prompt, operator) {
            if (handoffTarget != null) check(decisions > 0) { "The local operator did not decide a request for $handoffTarget." }
            if (case == AndroidUseRealCase.Contacts) {
                await("contact draft shows all four inputs") {
                    val root = automation.rootInActiveWindow ?: return@await false
                    root.packageName?.toString() == case.target &&
                        listOf("李", "星河", "Work").all { value ->
                            hasNode(root) { it.text?.toString() == value || it.contentDescription?.toString() == value }
                        } &&
                        hasNode(root) { it.text?.toString()?.filter(Char::isDigit) == "2025550147" } &&
                        hasNode(root) { it.text?.toString() == "Save" || it.contentDescription?.toString() == "Save" }
                }
                check(contactRows() == contactsBefore) { "Contact provider changed although this was a draft." }
            }
            if (case == AndroidUseRealCase.HandoffBrightness) {
                val display = shell("dumpsys display")
                fun value(key: String) = display.lineSequence().firstOrNull { "mCachedBrightnessInfo.$key=" in it }
                    ?.substringAfter("=")?.trim()?.toDoubleOrNull()
                val brightness = value("brightness")
                check(brightness != null && brightness == value("brightnessMin")) {
                    "Display brightness readback was $brightness."
                }
            }
            if (case == AndroidUseRealCase.HandoffSms) {
                await("the exact unsent draft in ABC's conversation") {
                    val root = automation.rootInActiveWindow ?: return@await false
                    root.packageName?.toString() == handoffTarget &&
                        hasNode(root) { it.text?.toString() == "ABC" } &&
                        hasNode(root) { it.viewIdResourceName?.endsWith("/compose_message_text") == true &&
                            it.text?.toString() == "Nice to meet you" }
                }
            }
        }
        buildString {
            appendLine("ANDROID_USE_REAL_RUN case=${case.name} backend=$backend elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
            if (case == AndroidUseRealCase.Receipt) appendLine("SCOPE_DENIAL_VERIFIED screenshot=wrong_app write=wrong_app tap=wrong_app")
            if (handoffTarget != null) appendLine("HANDOFF_UI_DECISION target=$handoffTarget allow=$allow count=$decisions")
            appendLine("TOOL_COUNTS ${final.toolCalls}")
            appendLine("AGENT_ANSWER ${final.answer.takeLast(1_000)}")
        }
    }

    private fun contactRows() = shell("content query --uri content://com.android.contacts/raw_contacts --projection _id")
        .also { check(!it.contains("Error") && !it.contains("Exception")) { "Cannot independently query contact rows." } }
        .lineSequence().filter { it.startsWith("Row: ") }.toSet()

    private fun launchApp(packageName: String) {
        val component = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName")
            .lineSequence().map(String::trim).firstOrNull { it.startsWith("$packageName/") }
            ?: error("No launcher activity for $packageName")
        val result = shell("am start -W -n $component")
        check(!result.contains("Error")) { result }
    }

    private suspend fun runTask(
        backend: AgentBackend,
        port: Int,
        model: String,
        target: String,
        prompt: String,
        operator: (suspend () -> Unit)? = null,
        verify: suspend () -> Unit,
    ): AgentTurn = coroutineScope {
        val app = sdk.applicationContext as SampleAgentHost
        val turn = app.androidUse.start(backend, prompt, ModelEndpoint("http://10.0.2.2:$port/v1/", model), target)
        val decision = operator?.let { launch { it() } }
        try {
            val final = withTimeout(180_000) { turn.state.first { it.status != RunStatus.Running } }
            check(final.status == RunStatus.Succeeded) { "${final.status}: ${final.message} (${final.answer.takeLast(280)})" }
            decision?.cancelAndJoin()
            try {
                verify()
            } catch (failure: IllegalStateException) {
                error("${failure.message} Agent answer: ${final.answer.takeLast(380)}")
            }
            final
        } finally {
            decision?.cancel()
            turn.cancel()
        }
    }

    suspend fun handoffBoundaries(): String = withFixtureService {
        launchFixture()
        val notes = AndroidUseRealCase.Markor.target
        val grant = withContext(Dispatchers.Main) { AndroidUseSession.start(sdk, fixture, handoffs = true) }
        try {
            val initial = structured(observe(grant, screenshot = true))
            launchApp(notes)
            await("Markor in front") { automation.rootInActiveWindow?.packageName?.toString() == notes }
            delay(350)
            coroutineScope {
                val oldTap = async { tap(grant, initial, "x" to 20, "y" to 20) }
                val allow = try { awaitConsent(notes, true) } catch (_: TimeoutCancellationException) {
                    error("No Markor approval. Tool result: ${if (oldTap.isCompleted) oldTap.await() else "pending"}")
                }
                check(!oldTap.isCompleted)
                check(error(observe(grant, screenshot = true)) == "authorization_pending")
                tapConsent(allow)
                val result = oldTap.await()
                check(error(result) == "stale_snapshot") { result.toString() }
            }
            val fresh = structured(observe(grant, screenshot = true))
            check(fresh["package"]?.jsonPrimitive?.content == notes && fresh["snapshotId"] != initial["snapshotId"])

            // Returning to the origin ends the one-window extension. Revisiting Markor asks again.
            launchFixture()
            await("a fresh origin observation") { error(observe(grant)) == null }
            launchApp(notes)
            await("Markor in front before changed destination check") {
                automation.rootInActiveWindow?.packageName?.toString() == notes
            }
            delay(350)
            coroutineScope {
                val read = async { observe(grant, screenshot = true) }
                val allow = awaitConsent(notes, true)
                launchFixture()
                tapConsent(allow)
                val result = read.await()
                check(error(result) == "authorization_expired") { result.toString() }
            }
            launchApp(notes)
            await("Markor in front again") { automation.rootInActiveWindow?.packageName?.toString() == notes }
            delay(350)
            coroutineScope {
                val read = async { observe(grant, screenshot = true) }
                val deny = awaitConsent(notes, false)
                check(!read.isCompleted)
                tapConsent(deny)
                val result = read.await()
                check(error(result) == "authorization_denied" && result.getValue("content").jsonArray.size == 1)
            }
            check(error(type(grant, initial, "blocked")) == "authorization_denied")
            check(error(tap(grant, fresh, "x" to 20, "y" to 20)) == "authorization_denied")
        } finally {
            withContext(Dispatchers.Main) { grant.close() }
        }
        "ANDROID_USE_HANDOFF_BOUNDARIES_PASS paused call, stale input, fresh destination snapshot, changed window, grant expiry, denied image and actions"
    }

    private suspend fun awaitConsent(target: String, allow: Boolean, timeoutMs: Long = 8_000): Rect {
        val label = if (allow) "Allow once" else "Deny"
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        return withTimeout(timeoutMs) {
            while (true) {
                val bounds = automation.windows.asSequence()
                    .filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                    .mapNotNull { it.root }
                    .firstOrNull { popup -> popup.packageName?.toString() == sdk.packageName && hasNode(popup) {
                        it.text?.toString()?.lines()?.contains(target) == true
                    } }
                    ?.findAccessibilityNodeInfosByText(label)
                    ?.firstOrNull { it.text?.toString() == label }
                    ?.let { Rect().also(it::getBoundsInScreen) }
                if (bounds != null && !bounds.isEmpty) return@withTimeout bounds
                delay(100)
            }
            error("No consent button for $target")
        }
    }

    private fun tapConsent(bounds: Rect) {
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    suspend fun semanticControls(): String = withFixtureService {
        val activity = "$fixture/dev.androidagent.sample.AndroidUseFixtureActivity"
        check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        check(!shell("am start -W -n $activity --ez semanticFixture true").contains("Error"))
        awaitText("Range readback 40")
        delay(350)
        val grant = withContext(Dispatchers.Main) { AndroidUseSession.start(sdk, fixture) }
        try {
            val initial = returnedObservation(grant.call("android_open_app", buildJsonObject {}))
            val range = elementByDescription(initial, "Test range")
            val values = range.getValue("range").jsonObject
            check(values["adjustable"]?.jsonPrimitive?.boolean == true && values["min"]?.jsonPrimitive?.float == 0f &&
                values["max"]?.jsonPrimitive?.float == 100f && values["current"]?.jsonPrimitive?.float == 40f)
            val invalid = semanticAction(grant, "set_progress", initial, range) { put("value", 101) }
            check(error(invalid) == "invalid_argument") { invalid.toString() }
            val set = semanticAction(grant, "set_progress", initial, range) { put("value", 75) }
            val beforeCheck = returnedObservation(set)
            check(beforeCheck.getValue("snapshotId") != initial.getValue("snapshotId"))
            check(elementByDescription(beforeCheck, "Test range").getValue("range").jsonObject["current"]?.jsonPrimitive?.float == 75f)
            awaitText("Range readback 75")
            check(error(semanticAction(grant, "set_progress", initial, range) { put("value", 30) }) == "stale_snapshot")

            val checkbox = element(beforeCheck, "Test checkbox")
            check(checkbox["checked"]?.jsonPrimitive?.boolean == false)
            val afterCheck = returnedObservation(semanticAction(grant, "tap", beforeCheck, checkbox))
            check(element(afterCheck, "Test checkbox")["checked"]?.jsonPrimitive?.boolean == true)
            val beforeScroll = returnedObservation(semanticAction(grant, "tap", afterCheck, element(afterCheck, "Test tab")))
            check(element(beforeScroll, "Test tab")["selected"]?.jsonPrimitive?.boolean == true)
            element(beforeScroll, "Result 1")
            val list = elementByDescription(beforeScroll, "Test results")
            check(list.getValue("scrollDirections").jsonArray.any { it.jsonPrimitive.content == "forward" })
            val unsupported = semanticAction(grant, "scroll", beforeScroll, list) { put("direction", "right") }
            check(error(unsupported) == "unsupported_action") { unsupported.toString() }
            val scroll = semanticAction(grant, "scroll", beforeScroll, list) { put("direction", "forward") }
            val afterScroll = returnedObservation(scroll)
            check(afterScroll.getValue("elements").jsonArray.none {
                it.jsonObject["text"]?.jsonPrimitive?.content == "Result 1"
            }) { "Native scroll did not move the list before returning the snapshot." }

            val back = structured(grant.call("android_back", buildJsonObject {
                put("snapshotId", afterScroll.getValue("snapshotId"))
            }))
            check(back["accepted"]?.jsonPrimitive?.boolean == true && back["observation"] == null &&
                back["observationError"]?.jsonPrimitive?.content == "wrong_app") {
                "Back opened a different app without authorization: $back"
            }
            withContext(Dispatchers.Main) { AndroidUseSession.revoke() }
            check(error(semanticAction(grant, "scroll", beforeScroll, list) { put("direction", "backward") }) == "revoked")
        } finally {
            withContext(Dispatchers.Main) { grant.close() }
        }
        "ANDROID_USE_SEMANTIC_CONTROLS_PASS post-action range, checked/selected state, native scroll, stale/revoked actions and omitted cross-app state"
    }

    private fun returnedObservation(result: JsonObject): JsonObject {
        val body = structured(result)
        check(body["observation"] != null) { "Missing post-action snapshot: $body" }
        return body.getValue("observation").jsonObject
    }

    private fun elementByDescription(snapshot: JsonObject, value: String) = snapshot.getValue("elements").jsonArray
        .map(JsonElement::jsonObject).single { it["description"]?.jsonPrimitive?.content == value }

    private suspend fun semanticAction(
        grant: AndroidUseSession, tool: String, snapshot: JsonObject, target: JsonObject,
        options: JsonObjectBuilder.() -> Unit = {},
    ) = grant.call("android_$tool", buildJsonObject {
        put("snapshotId", snapshot.getValue("snapshotId"))
        put("id", target.getValue("id"))
        options()
    })

    suspend fun boundaries(): String = withFixtureService {
        launchFixture()
        val grant = withContext(Dispatchers.Main) { AndroidUseSession.start(sdk, fixture) }
        try {
            val initial = observe(grant, screenshot = true)
            val first = structured(initial)
            val screenshot = initial.getValue("content").jsonArray.last().jsonObject
            check(screenshot["type"]?.jsonPrimitive?.content == "image")
            check(screenshot["mimeType"]?.jsonPrimitive?.content == "image/jpeg")
            check(screenshot.getValue("data").jsonPrimitive.content.length > 1_000)
            val second = structured(observe(grant))
            check(first["snapshotId"] != second["snapshotId"])
            val chinese = "端侧操作验证，中文输入成功。"
            check(error(type(grant, first, chinese)) == "stale_snapshot")
            check(error(type(grant, second, chinese)) == null)
            awaitText(chinese)
            check(error(type(grant, second, "STALE")) == "stale_snapshot")
            awaitText(chinese)

            val beforeSwitch = structured(observe(grant))
            check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            await("foreground app switch") { automation.rootInActiveWindow?.packageName?.toString()?.let { it != fixture } == true }
            check(error(observe(grant)) == "wrong_app")
            check(error(type(grant, beforeSwitch, "WRONG")) == "wrong_app")
            val opened = grant.call("android_open_app", buildJsonObject {})
            check(structured(opened)["inForeground"]?.jsonPrimitive?.boolean == true) { opened.toString() }
            awaitText("Waiting for code")
            val afterReturn = type(grant, beforeSwitch, "STALE")
            check(error(afterReturn) == "stale_snapshot") { afterReturn.toString() }
            launchFixture(protected = true)
            check(error(observe(grant, screenshot = true)) == "protected_window")
            check(error(observe(grant)) == "protected_window")
            checkTapTargets(grant)
            launchFixture()
            val last = structured(observe(grant))
            withContext(Dispatchers.Main) { AndroidUseSession.revoke() }
            check(error(observe(grant, screenshot = true)) == "revoked")
            check(error(type(grant, last, "REVOKED")) == "revoked")
        } finally {
            withContext(Dispatchers.Main) { grant.close() }
        }
        "ANDROID_USE_BOUNDARIES_PASS screenshot, Chinese input, old snapshot, switch App, relaunch, protected and revoked access, semantic tap and coordinate fallback"
    }

    private suspend fun checkTapTargets(grant: AndroidUseSession) {
        val activity = "$fixture/dev.androidagent.sample.AndroidUseFixtureActivity"
        check(!shell("am start -W -n $activity --ez tapFixture true").contains("Error"))
        awaitText("Ready for tap")

        val rowSnapshot = structured(observe(grant))
        val title = element(rowSnapshot, "Test list result")
        check(title["clickable"] == null && title["tapTargetId"] != null) { title.toString() }
        check(error(tap(grant, rowSnapshot, "id" to title.getValue("id").jsonPrimitive.int)) == null)
        awaitText("Row tapped")

        val coordinateSnapshot = structured(observe(grant))
        val target = element(coordinateSnapshot, "Coordinate target")
        val bounds = target.getValue("bounds").jsonArray.map { it.jsonPrimitive.int }
        val xy = arrayOf("x" to (bounds[0] + bounds[2]) / 2, "y" to (bounds[1] + bounds[3]) / 2)
        check(error(tap(grant, coordinateSnapshot, "id" to target.getValue("id").jsonPrimitive.int)) == "unsupported_action")
        val revision = withContext(Dispatchers.Main) { AndroidUseService.connected?.revision }
        clickFixtureControl("Change outside")
        awaitText("Outside changed")
        await("accessibility content revision changed") {
            withContext(Dispatchers.Main) { AndroidUseService.connected?.revision != revision }
        }
        val fallback = tap(grant, coordinateSnapshot, *xy)
        check(error(fallback) == null) { fallback.toString() }
        awaitText("Coordinate tapped")
        check(error(tap(grant, coordinateSnapshot, *xy)) == "stale_snapshot")

        val beforeChange = structured(observe(grant, screenshot = true))
        clickFixtureControl("Change target")
        automation.waitForIdle(100, 2_000)
        val stale = tap(grant, beforeChange, *xy)
        check(error(stale) == "stale_snapshot" && structuredErrorMessage(stale).contains("tap area changed")) {
            stale.toString()
        }
        val changedSnapshot = structured(observe(grant))
        check(error(tap(grant, changedSnapshot, *xy)) == null)
    }

    private fun clickFixtureControl(text: String) {
        val control = automation.rootInActiveWindow.findAccessibilityNodeInfosByText(text).single()
        check(control.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not click $text" }
    }

    private fun element(snapshot: JsonObject, text: String) = snapshot.getValue("elements").jsonArray
        .map(JsonElement::jsonObject).single { it["text"]?.jsonPrimitive?.content == text }

    private suspend fun tap(grant: AndroidUseSession, snapshot: JsonObject, vararg args: Pair<String, Int>) =
        grant.call("android_tap", buildJsonObject {
            put("snapshotId", snapshot.getValue("snapshotId"))
            for ((key, value) in args) put(key, value)
        })

    private fun structuredErrorMessage(result: JsonObject) =
        result.getValue("structuredContent").jsonObject.getValue("message").jsonPrimitive.content

    private suspend fun withFixtureService(block: suspend () -> String): String {
        check(android.os.Build.VERSION.SDK_INT == 37 && android.os.Build.HARDWARE == "ranchu") {
            "Run this probe only on an Android 17 emulator."
        }
        val key = "enabled_accessibility_services"
        val previous = shell("settings get secure $key").trim()
        val service = "${sdk.packageName}/${AndroidUseService::class.java.name}"
        val services = previous.split(':').filter { it.isNotEmpty() && it != "null" && it != service }
        fun setServices(value: String) {
            if (value.isEmpty() || value == "null") shell("settings delete secure $key")
            else shell("settings put secure $key $value")
        }
        // am instrument restarts its target process. Rebind the fixture's service after that restart.
        setServices(services.joinToString(":"))
        try {
            setServices((services + service).joinToString(":"))
            await("fixture accessibility service") { withContext(Dispatchers.Main) { AndroidUseService.connected != null } }
            return block()
        } finally {
            setServices(previous)
        }
    }

    private suspend fun launchFixture(protected: Boolean = false) {
        val result = shell("am start -W -n $fixture/dev.androidagent.sample.AndroidUseFixtureActivity --ez protected $protected")
        check(!result.contains("Error")) { result }
        awaitText("Waiting for code")
        delay(350)
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }

    private suspend fun observe(grant: AndroidUseSession, screenshot: Boolean = false) =
        grant.call("android_observe", buildJsonObject { put("screenshot", screenshot) })

    private suspend fun type(grant: AndroidUseSession, snapshot: JsonObject, text: String): JsonObject {
        val entry = snapshot.getValue("elements").jsonArray.map(JsonElement::jsonObject)
            .single { it["description"]?.jsonPrimitive?.content == "Code entry" }
        return grant.call("android_type", buildJsonObject {
            put("id", entry.getValue("id"))
            put("snapshotId", snapshot.getValue("snapshotId"))
            put("text", text)
        })
    }

    private fun structured(result: JsonObject): JsonObject {
        check(error(result) == null) { result.toString().take(650) }
        val text = result.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun error(result: JsonObject) = result["structuredContent"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    private suspend fun awaitText(text: String) {
        await("test app text: $text") {
            val root = automation.rootInActiveWindow
            root?.packageName?.toString() == fixture && hasNode(root) { it.text?.toString() == text }
        }
    }

    private fun hasNode(node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): Boolean =
        matches(node) || (0 until node.childCount).any { index ->
            node.getChild(index)?.let { hasNode(it, matches) } == true
        }

    private suspend fun await(label: String, condition: suspend () -> Boolean) {
        val seen = kotlinx.coroutines.withTimeoutOrNull(8_000) {
            while (!condition()) delay(100)
            true
        }
        check(seen == true) { "Timed out waiting for $label." }
    }
}
