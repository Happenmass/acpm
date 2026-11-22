package dev.androidagent.sample

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import dev.androidagent.sdk.AgentBackend
import dev.androidagent.sdk.AgentRuntime
import dev.androidagent.sdk.ApprovalKind
import dev.androidagent.sdk.DeviceSupport
import dev.androidagent.sdk.RunStatus
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.serialization.json.JsonPrimitive

class SampleProbe : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        this.arguments = arguments
        start()
    }

    override fun onStart() {
        val result = runCatching { runBlocking {
            check(targetContext.packageName.endsWith(".fixture")) { "Build with -PsampleFixture for an isolated sample." }
            when (arguments.getString("stage")) {
                "speech" -> verifySpeech()
                "speech-diagnostics" -> SpeechFailureProbe(targetContext).run()
                "speech-diagnostics-restart" -> verifySpeechDiagnosticRestart()
                "htp-benchmark" -> benchmarkHtp()
                "htp-tokens" -> verifyPublicTokens()
                "recover" -> verifyRecovery()
                "remote" -> verifyRemote()
                "android-use", "android-use-clock-timer", "android-use-real-app" -> {
                    val probe = AndroidUseProbe(this@SampleProbe)
                    val backend = if (arguments.getString("backend") == "codex") AgentBackend.Codex else AgentBackend.Pi
                    val port = requireNotNull(arguments.getString("gatewayPort")).toInt()
                    val model = requireNotNull(arguments.getString("model"))
                    when (arguments.getString("stage")) {
                        "android-use-clock-timer" -> probe.clockTimer(backend, port, model)
                        "android-use-real-app" -> probe.realApp(backend, port, model,
                            AndroidUseRealCase.valueOf(requireNotNull(arguments.getString("case"))))
                        else -> probe.run(backend, port, model)
                    }
                }
                "android-use-boundaries" -> AndroidUseProbe(this@SampleProbe).boundaries()
                "android-use-handoff-boundaries" -> AndroidUseProbe(this@SampleProbe).handoffBoundaries()
                "android-use-semantic-controls" -> AndroidUseProbe(this@SampleProbe).semanticControls()
                "unsupported" -> verifyUnsupportedDevice()
                else -> verify()
            }
        } }
        val output = result.getOrElse(Throwable::stackTraceToString)
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString(REPORT_KEY_STREAMRESULT, output) })
    }

    private suspend fun verifyPublicTokens(): String = withContext(Dispatchers.IO) {
        val models = SpeechModels(targetContext)
        models.prepareAsr()
        val revision = requireNotNull(arguments.getString("revision"))
        require(revision.matches(Regex("[0-9a-f]{16}")))
        val dir = File(targetContext.noBackupFilesDir, "speech-models/qwen-$revision")
        val runtime = File(models.directory, "qnn").absolutePath
        val wav = File(targetContext.filesDir, "public-24s.wav")
        val expected = File(targetContext.filesDir, "public-token-ids.txt").readText().trim().split(",").map(String::toInt)
        require(expected.size == 75 && wav.isFile)
        val exe = File(targetContext.applicationInfo.nativeLibraryDir, "libandroid_ai_qwen_htp.so")
        fun startWorker(vararg args: String) = ProcessBuilder(listOf(exe.absolutePath) + args).apply {
            environment()["LD_LIBRARY_PATH"] = "$runtime:/system/lib64:/system_ext/lib64:/vendor/lib64"
            environment()["ADSP_LIBRARY_PATH"] = runtime
            environment()["QWEN_HTP_SHARED_IO"] = "1"
            environment()["QWEN_HTP_PERFORMANCE"] = "1"
        }.redirectErrorStream(true).start()
        val metadata = targetContext.assets.open("qwen-htp-manifest.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
        if (metadata.has("htpArch")) {
            val probe = startWorker("--device-arch", runtime)
            try {
                val expectedArch = metadata.getInt("htpArch")
                val lines = probe.inputStream.bufferedReader().readLines()
                check(probe.waitFor() == 0 && "QWEN_HTP_ARCH $expectedArch" in lines) { "HTP architecture query mismatch" }
            } finally {
                probe.destroyForcibly()
            }
        }
        val child = startWorker(runtime, dir.absolutePath, "60000", "--token-ids")
        try {
            child.outputStream.bufferedWriter().use { it.write("1\tburst\t${wav.absolutePath}\nquit\n") }
            val output = child.inputStream.bufferedReader().readLines()
            check(child.waitFor() == 0) { "Fixture worker failed for $revision" }
            val ids = output.single { it.startsWith("SOUND_QWEN_TOKEN_IDS\t") }.split('\t').drop(1).map(String::toInt)
            check(ids == expected) { "Token mismatch for $revision at ${expected.indices.firstOrNull { ids.getOrNull(it) != expected[it] }}" }
            "Token verification passed for $revision: ${ids.joinToString(",")}"
        } finally {
            child.destroyForcibly()
        }
    }

    private suspend fun benchmarkHtp(): String {
        val directory = File(targetContext.filesDir, "speech")
        val wav = File(directory, "fixture.wav")
        val models = SpeechModels(targetContext)
        withContext(Dispatchers.IO) { models.prepareAsr() }
        val idleMs = 5_000
        var expectedText: String? = null
        val rows = mutableListOf<String>()
        repeat(3) { round ->
            for (cold in listOf(true, false)) {
                // Each request uses a new client, like a new recording or recreated Activity.
                val client = models.asr(idleMs)
                val start = SystemClock.elapsedRealtimeNanos()
                val result = client.transcribe(wav)
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                val loadMs = checkNotNull(result.modelLoadMs)
                check(if (cold) loadMs > 0 else loadMs == 0.0) { "Unexpected model load on request ${rows.size + 1}: $loadMs ms" }
                val timing = result.segments.single().timing
                val text = result.text
                check(text.contains("play basketball", ignoreCase = true) && text.contains("喜欢")) { text }
                expectedText = expectedText ?: text
                check(text == expectedText) { "The transcript changed on reuse or reload." }
                rows += "HTP_BENCH ${round + 1} ${if (cold) "cold" else "warm"} " +
                    "$elapsedMs $loadMs ${timing.melMs} ${timing.encoderMs} ${timing.prefillMs} ${timing.decodeMs} " +
                    "${timing.decodeGraphMs} ${timing.decodeKvMs} ${timing.decodeOtherMs} " +
                    "${timing.decodeCalls} ${timing.tokens} ${timing.workerPeakRssKiB}"
            }
            if (round < 2) delay(idleMs + 1_000L)
        }
        return (rows + "HTP benchmark passed. All six transcriptions matched.").joinToString("\n")
    }

    private suspend fun verifySpeech(): String {
        val activity = startActivitySync(Intent(targetContext, SpeechActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun viewText(test: (String) -> Boolean): TextView =
                control<TextView>(activity) { test(it.text.toString()) }
            suspend fun tap(button: String, prefix: String): String {
                runOnMainSync { control<Button>(activity) { it.text == button }.performClick() }
                return withTimeout(90_000) {
                    while (true) {
                        var output: String? = null
                        runOnMainSync {
                            output = runCatching { viewText { it.startsWith(prefix) }.text.toString() }.getOrNull()
                        }
                        output?.let { return@withTimeout it }
                        delay(50)
                    }
                    error("Unreachable")
                }
            }
            val vad = tap("Detect speech", "VAD:")
            check(Regex("VAD: [1-9][0-9]* speech ranges").matches(vad)) { vad }
            val embedding = tap("Extract speaker embedding", "Embedding:")
            check(Regex("Embedding: [1-9][0-9]* dimensions").matches(embedding)) { embedding }
            val cluster = tap("Cluster speaker embeddings", "Clustering:")
            check(cluster == "Clustering: 1 speaker(s)") { cluster }
            val asr = tap("Transcribe with HTP", "HTP ASR:")
            if (arguments.getString("requireHtp") == "true") {
                check(Regex("HTP ASR: [1-9][0-9]* windows").containsMatchIn(asr)) { asr }
                val transcript = asr.substringAfter('\n').substringBeforeLast('\n')
                check(transcript.isNotBlank()) { asr }
                arguments.getString("expectedPhrase")?.let { phrase ->
                    check(transcript.contains(phrase, ignoreCase = true)) { "Missing expected synthetic phrase: $transcript" }
                }
                return "Speech sample passed. $vad. $embedding. $cluster. $asr"
            } else {
                check(asr == "HTP ASR: Qualcomm HTP unavailable (UNSUPPORTED_DEVICE).") { asr }
            }
            return "Speech sample passed. $vad. $embedding. $cluster. ${asr.substringBefore('\n')}."
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private suspend fun verifySpeechDiagnosticRestart(): String {
        SpeechFailureProbe(targetContext).verifyRestart()
        val console = startActivitySync(Intent(targetContext, ConsoleActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            withTimeout(8_000) {
                var visible = false
                while (!visible) {
                    runOnMainSync {
                        visible = runCatching {
                            control<TextView>(console) { it.text.toString().startsWith("QNN context metadata · 0x") }
                        }.isSuccess
                    }
                    if (!visible) delay(50)
                }
            }
        } finally {
            runOnMainSync { console.finish() }
        }
        return "Speech failure history survived an SDK process restart and appeared in the console."
    }

    private suspend fun verify(): String {
        check(DeviceSupport.check(targetContext) is DeviceSupport.Supported) { "A device with app-level Landlock is required." }
        val useCodex = arguments.getString("backend") == "codex"
        val stage = arguments.getString("stage")
        if (Build.VERSION.SDK_INT >= 33) {
            uiAutomation.grantRuntimePermission(targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val answerGate = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (!answerGate.await(12, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                    return MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody(if (useCodex) responses() else completions())
                }
            }
            server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val runtime = AgentRuntime.get(targetContext)
            var screenSlept = false
            try {
                waitFor { control<Button>(activity) { it.text.toString() == "Run task" }.isEnabled }
                val submitted = SystemClock.elapsedRealtime()
                submitForm(activity, useCodex, "http://127.0.0.1:${server.port}/v1/", "sample-fixture", background = true)
                val handle = withTimeout(5000) { runtime.activeRun.filterNotNull().first() }
                val route = if (useCodex) "/v1/responses" else "/v1/chat/completions"
                check(server.takeRequest(7, TimeUnit.SECONDS)?.path == route)
                val firstRequestMs = SystemClock.elapsedRealtime() - submitted
                val manager = targetContext.getSystemService(ActivityManager::class.java)
                @Suppress("DEPRECATION")
                waitFor { manager.getRunningServices(10).any { it.service.className == AgentTaskService::class.java.name && it.foreground } }
                if (stage == "interrupt") {
                    File(runtime.workspace("demo"), "recovery-fixture.txt").writeText(handle.id)
                    sendStatus(1, Bundle().apply { putString("agent_run_ready", handle.id) })
                    delay(9000)
                    error("The probe requires a force-stop after the ready event.")
                }
                if (stage == "lock") {
                    val power = targetContext.getSystemService(PowerManager::class.java)
                    val keyguard = targetContext.getSystemService(KeyguardManager::class.java)
                    check(power.isInteractive) { "Start the probe with the screen on." }
                    screenSlept = true
                    uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
                    waitFor { !power.isInteractive && keyguard.isKeyguardLocked }
                    check(runtime.activeRun.value?.id == handle.id)
                    answerGate.countDown()
                    val final = withTimeout(7000) { handle.state.first { it.status != RunStatus.Running } }
                    check(final.status == RunStatus.Succeeded && final.answer.endsWith("Sample agent finished."))
                    check(!power.isInteractive && keyguard.isKeyguardLocked) { "The device left the locked state before completion." }
                    return "Screen-off probe passed: ${if (useCodex) "Codex" else "Pi"}, keyguardShowing=true, firstRequestMs=$firstRequestMs."
                }
                if (stage == "profile") delay(1_800)
                // Recreate the Activity while the SDK's task and foreground service are still waiting for the model.
                runOnMainSync { activity.recreate() }
                waitFor { activity.isDestroyed }
                check(runtime.activeRun.value?.id == handle.id)
                answerGate.countDown()
                val final = withTimeout(7000) { handle.state.first { it.status != RunStatus.Running } }
                // Codex may emit its unknown-model warning before the actual streamed answer.
                check(final.status == RunStatus.Succeeded && final.answer.endsWith("Sample agent finished.")) { final.toString() }
                @Suppress("DEPRECATION")
                waitFor { manager.getRunningServices(10).none { it.service.className == AgentTaskService::class.java.name } }
                return "Sample UI probe passed: ${if (useCodex) "Codex" else "Pi"} task survived Activity recreation with an active foreground service. The service stopped after completion. firstRequestMs=$firstRequestMs"
            } finally {
                answerGate.countDown()
                runtime.activeRun.value?.cancel()
                if (screenSlept) uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            }
        }
    }

    private suspend fun verifyRemote(): String = coroutineScope {
        val useCodex = arguments.getString("backend") == "codex"
        val port = requireNotNull(arguments.getString("gatewayPort")?.toIntOrNull()).also { require(it in 1..65535) }
        val host = arguments.getString("gatewayHost") ?: "127.0.0.1"
        require(host in setOf("127.0.0.1", "10.0.2.2"))
        val model = requireNotNull(arguments.getString("model"))
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val runtime = AgentRuntime.get(targetContext)
        waitFor { control<Button>(activity) { it.text.toString() == "Run task" }.isEnabled }
        submitForm(activity, useCodex, "http://$host:$port/v1/", model, background = false)
        val run = withTimeout(5000) { runtime.activeRun.filterNotNull().first() }
        val approvals = launch {
            run.approval.filterNotNull().collect { request ->
                val option = request.options.firstOrNull {
                    it.kind == ApprovalKind.ALLOW_ONCE && request.hostToolName == "read_note"
                }
                run.respondToApproval(request.id, option?.id)
            }
        }
        try {
            val final = withTimeout(65_000) { run.state.first { it.status != RunStatus.Running } }
            check(final.status == RunStatus.Succeeded) { final.message ?: "The sample task failed." }
            check(final.answer.contains("Kepler", ignoreCase = true)) { "The sample did not return the synthetic note codename." }
            "Sample remote model probe passed: ${if (useCodex) "Codex" else "Pi"} returned the synthetic note codename."
        } finally {
            if (run.state.value.status == RunStatus.Running) {
                run.cancel()
                withTimeout(5000) { run.state.first { it.status != RunStatus.Running } }
            }
            approvals.cancelAndJoin()
        }
    }

    private suspend fun verifyUnsupportedDevice(): String {
        val support = DeviceSupport.check(targetContext)
        val diagnostic = when (support) {
            DeviceSupport.IsolationBlockedByAppPolicy -> "Android app policy blocks Landlock on this device."
            is DeviceSupport.IsolationUnavailable -> "This device cannot provide Landlock ABI 6."
            else -> error("Expected an installed launcher on a device without usable Landlock, got $support.")
        }
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor { activity.window.decorView.descendants().filterIsInstance<TextView>().any { it.text == diagnostic } }
        check(!control<Button>(activity) { it.text == "Run task" }.isEnabled)
        check(AgentRuntime.get(targetContext).activeRun.value == null)
        return "Unsupported-device probe passed: $diagnostic"
    }

    private fun submitForm(activity: Activity, useCodex: Boolean, baseUrl: String, model: String, background: Boolean) {
        runOnMainSync {
            val selection = control<Spinner>(activity) { true }
            val wanted = if (useCodex) "Codex" else "Pi"
            val index = (0 until selection.count).first { selection.getItemAtPosition(it).toString().startsWith(wanted) }
            selection.setSelection(index)
            control<EditText>(activity) { it.hint == "Model base URL" }.setText(baseUrl)
            control<EditText>(activity) { it.hint == "Model identifier" }.setText(model)
            control<CheckBox>(activity) { it.text == "Start a new session" }.isChecked = true
            control<CheckBox>(activity) { it.text == "Keep running when I leave the app" }.isChecked = background
            control<Button>(activity) { it.text.toString() == "Run task" }.performClick()
        }
    }

    private fun completions() =
        """data: {"id":"sample","object":"chat.completion.chunk","created":0,"model":"sample-fixture","choices":[{"index":0,"delta":{"role":"assistant","content":"Sample agent finished."},"finish_reason":null}]}""" +
            "\n\n" +
            """data: {"id":"sample","object":"chat.completion.chunk","created":0,"model":"sample-fixture","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" +
            "\n\ndata: [DONE]\n\n"

    private fun responses(): String {
        val item = """{"type":"message","id":"msg_sample","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Sample agent finished.","annotations":[]}]}"""
        fun response(status: String, items: String) =
            """{"id":"resp_sample","object":"response","model":"sample-fixture","created_at":0,"status":${JsonPrimitive(status)},"output":$items}"""
        val frames = listOf(
            "response.created" to ("response" to response("in_progress", "[]")),
            "response.output_item.added" to ("item" to item),
            "response.output_text.delta" to ("delta" to JsonPrimitive("Sample agent finished.").toString()),
            "response.output_item.done" to ("item" to item),
            "response.completed" to ("response" to response("completed", "[$item]")),
        )
        return frames.joinToString("") { (type, field) ->
            "event: $type\ndata: {\"type\":\"$type\",\"output_index\":0,\"${field.first}\":${field.second}}\n\n"
        }
    }

    private suspend fun verifyRecovery(): String {
        val id = checkNotNull(arguments.getString("runId"))
        val runtime = AgentRuntime.get(targetContext)
        val records = runtime.sessions().flatMap { runtime.history(it) }
        val interrupted = records.single { it.id == id }
        check(interrupted.status == RunStatus.Interrupted && runtime.activeRun.value == null)
        check(File(runtime.workspace("demo"), "recovery-fixture.txt").readText() == id)
        check(File(targetContext.cacheDir, "android-agent-tasks").list().orEmpty().isEmpty())
        return "Sample process-death probe passed: last task interrupted, artifact preserved, scratch removed."
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(6000) {
        while (!condition()) delay(50)
    }

    private inline fun <reified T : View> control(activity: Activity, crossinline matches: (T) -> Boolean): T =
        activity.window.decorView.descendants().filterIsInstance<T>().first { matches(it) }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }
}
