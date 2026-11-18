package dev.androidagent.sdk

import dev.androidagent.sample.SampleMcpServer
import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy

internal object PhysicalRuntimeProbe {
    suspend fun verify(context: Context) {
        if (DeviceSupport.check(context) !is DeviceSupport.Supported) return
        val runtime = AgentRuntime.get(context)
        if (runtime.installedBackends().map { it.backend }.toSet() != AgentBackend.entries.toSet()) return

        val scopeId = "physical-probe-${UUID.randomUUID()}"
        val tag = "artifact-${UUID.randomUUID()}"
        val workspace = runtime.workspace(scopeId).apply { mkdirs() }
        val artifact = File(workspace, "handoff.txt")
        val pi = runtime.openSession(AgentBackend.Pi, scopeId)
        val codex = runtime.openSession(AgentBackend.Codex, scopeId)
        val script = runtime.workspaceScriptTool(scopeId)
        val preExistingTemporary = temporaryTasks(context)
        try {
            verifyIsolationAndProcessGroup(context)
            val codexAnswer = runScript(runtime, codex, script, tag, """
                const fs = require('fs');
                const file = require('path').join(process.env.ANDROID_AGENT_WORKSPACE, 'handoff.txt');
                fs.writeFileSync(file, '$tag');
                console.log(fs.readFileSync(file, 'utf8'));
            """.trimIndent())
            check(tag in codexAnswer && artifact.readText() == tag)

            val piAnswer = runScript(runtime, pi, script, tag, """
                const file = require('path').join(process.env.ANDROID_AGENT_WORKSPACE, 'handoff.txt');
                console.log(require('fs').readFileSync(file, 'utf8'));
            """.trimIndent())
            check(tag in piAnswer)
            check(runtime.history(codex).single().status == RunStatus.Succeeded)
            check(runtime.history(pi).single().status == RunStatus.Succeeded)
            verifyModelRejection(runtime, scopeId)
            verifyCancelAndBusy(runtime, pi, tag)
            check(temporaryTasks(context) == preExistingTemporary) { "Temporary task files were retained." }
            check(artifact.readText() == tag) { "Cancelling deleted a completed workspace artifact." }
            runtime.deleteSession(codex)
            check(artifact.readText() == tag) { "Deleting a session removed its shared workspace artifact." }
        } finally {
            runtime.sessions().filter { it.workspace == scopeId }.forEach { runtime.deleteSession(it) }
            runtime.deleteWorkspace(scopeId)
        }
        check(!workspace.exists() && runtime.sessions().none { it.workspace == scopeId })
        Log.i("AndroidAgentProbe", "Physical runtime: file handoff, context clear, busy/cancel and storage lifecycle verified")
    }

    private suspend fun verifyModelRejection(runtime: AgentRuntime, scopeId: String) {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(401).setBody("synthetic internal error body")
            }
            server.start(loopback, 0)
            val endpoint = ModelEndpoint(server.url("/v1/").toString(), "fixture-model")
            for (backend in AgentBackend.entries) {
                val session = runtime.openSession(backend, scopeId)
                val run = runtime.submit(session, "Test rejected model credentials", endpoint)
                val outcome = awaitTerminal(run)
                check(outcome.status == RunStatus.Failed && outcome.message?.contains("HTTP 401") == true)
                check("synthetic internal error body" !in outcome.answer)
                runtime.deleteSession(session)
            }
        }
    }

    private fun verifyIsolationAndProcessGroup(context: Context) {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "agent-boundaries-").toFile()
        try {
            val scratch = File(root, "temporary").apply { mkdir() }
            val workspace = File(root, "workspace").apply { mkdir() }
            val privateFile = File(root, "private.txt").apply { writeText("outside") }
            File(workspace, "allowed.txt").writeText("inside")
            val program = File(scratch, "probe.cjs")
            val native = File(context.applicationInfo.nativeLibraryDir)
            val node = File(native, "libandroid_agent_node.so")
            val launcher = File(native, "libandroid_agent_sandbox.so")
            fun start(scriptMode: Boolean): Process = ProcessBuilder(
                launcher.absolutePath, scratch.absolutePath, native.absolutePath, node.absolutePath, program.absolutePath,
            ).directory(scratch).redirectError(File(root, "probe-stderr")).apply {
                environment().apply {
                    clear()
                    put("ANDROID_AGENT_WORKSPACE", workspace.absolutePath)
                    if (scriptMode) put("ANDROID_AGENT_SCRIPT", "1")
                }
            }.start()

            program.writeText("""
                const fs = require('fs');
                if (fs.readFileSync(${JsonPrimitive(File(workspace, "allowed.txt").path)}, 'utf8') !== 'inside') process.exit(3);
                try { fs.readFileSync(${JsonPrimitive(privateFile.path)}); process.exit(4); }
                catch (blocked) { if (blocked.code !== 'EACCES') process.exit(5); }
                const denied = require('net').connect(17767, '127.0.0.1');
                denied.on('connect', () => process.exit(6));
                denied.on('error', error => process.exit(error.code === 'EPERM' || error.code === 'EACCES' ? 0 : 7));
            """.trimIndent())
            val proof = start(scriptMode = true)
            try {
                check(proof.waitFor(6, TimeUnit.SECONDS) && proof.exitValue() == 0) {
                    "Workspace or network restriction failed: ${File(root, "probe-stderr").readText()}"
                }
            } finally {
                if (proof.isAlive) proof.destroyForcibly()
            }

            program.writeText("""
                const child = require('child_process').spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'],
                    { detached: true, stdio: 'ignore' });
                console.log(child.pid);
                setInterval(() => {}, 1000);
            """.trimIndent())
            val process = start(scriptMode = false)
            try {
                val input = process.inputStream.bufferedReader()
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (!input.ready() && process.isAlive && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                check(input.ready()) { "Detached child failed to start: ${File(root, "probe-stderr").readText()}" }
                val pid = checkNotNull(input.readLine().toIntOrNull())
                val child = File("/proc/$pid/stat")
                check(child.exists()) { "Detached child was not observable before cancellation." }
                process.destroy()
                check(process.waitFor(5, TimeUnit.SECONDS) && !child.exists()) { "Detached agent child survived cancellation." }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            Log.i("AndroidAgentProbe", "Physical isolation: outside workspace read and script TCP denied, detached child reclaimed")
        } finally {
            deleteAgentFiles(root)
        }
    }

    private suspend fun runScript(
        runtime: AgentRuntime, session: AgentSession, script: AgentTool, tag: String, source: String,
    ): String = coroutineScope {
        val model = "fixture-model"
        val secret = "synthetic-fixture-token"
        val fixture = ToolCallingModel(session.backend, model, secret, tag, true, script.name,
            buildJsonObject { put("source", source) }.toString())
        MockWebServer().use { server ->
            server.dispatcher = fixture
            server.start(loopback, 0)
            val mcp = SampleMcpServer(this@coroutineScope, listOf(script))
            val run = runtime.submitWithHostMcp(session, "Execute the workspace script and report its result.",
                ModelEndpoint(server.url("/v1/").toString(), model, secret), mcp.binding)
            val approvals = launch {
                run.approval.filterNotNull().collect { request ->
                    val option = request.options.firstOrNull { it.kind == ApprovalKind.ALLOW_ONCE }
                    check(run.respondToApproval(request.id, option?.id))
                }
            }
            try {
                val outcome = awaitTerminal(run)
                check(outcome.status == RunStatus.Succeeded && fixture.observedResult) {
                    "${session.backend}: ${outcome.status} ${outcome.message}, model events: ${fixture.events}"
                }
                outcome.answer
            } finally {
                if (run.state.value.status == RunStatus.Running) run.cancel()
                approvals.cancelAndJoin()
                mcp.close()
            }
        }
    }

    private suspend fun verifyCancelAndBusy(runtime: AgentRuntime, existing: AgentSession, oldContextMarker: String) {
        val original = existing.backend
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            server.start(loopback, 0)
            val fresh = runtime.clearSession(existing)
            check(fresh.backend == original && runtime.history(fresh).isEmpty())
            check(existing !in runtime.sessions())
            val model = ModelEndpoint(server.url("/v1/").toString(), "fixture-model", "synthetic-fixture-token")
            val run = runtime.submit(fresh, "Wait indefinitely for a model answer", model)
            try {
                val request = checkNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                check(oldContextMarker !in request.body.readUtf8()) { "Cleared context reached the model." }
                val rejected = runCatching { runtime.submit(fresh, "Second task", model) }.exceptionOrNull()
                check(rejected is AgentBusyException)
                run.cancel()
                val outcome = awaitTerminal(run)
                check(outcome.status == RunStatus.Cancelled)
                check(runtime.history(fresh).single().status == RunStatus.Cancelled)
            } finally {
                if (run.state.value.status == RunStatus.Running) {
                    run.cancel()
                    awaitTerminal(run)
                }
                runtime.deleteSession(fresh)
            }
        }
    }

    private suspend fun awaitTerminal(run: AgentRun): AgentTurn =
        withTimeout(20_000) { run.state.first { it.status != RunStatus.Running } }

    private fun temporaryTasks(context: Context): Set<String> =
        File(context.cacheDir, "android-agent-tasks").list().orEmpty().toSet()

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
}
