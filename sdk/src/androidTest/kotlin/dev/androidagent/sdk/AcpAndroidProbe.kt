package dev.androidagent.sdk

import dev.androidagent.sample.SampleMcpServer
import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.JsonRpcException
import java.io.File
import java.nio.file.Files
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Exercises the published ACP Kotlin library and a real stdio process under Android ART. */
class AcpAndroidProbe : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        this.arguments = arguments
        start()
    }

    override fun onStart() {
        val result = runCatching {
            runBlocking(Dispatchers.IO) {
                if (arguments.getString("stage") == "remote") {
                    return@runBlocking RemoteModelProbe.run(targetContext, arguments)
                }
                val support = DeviceSupport.check(targetContext)
                if (arguments.getString("requireIsolation") == "true") {
                    check(support is DeviceSupport.Supported) { "Expected app-level Landlock, got $support." }
                }
                withTimeout(140_000) {
                    val workspace = targetContext.cacheDir
                    val nativeId = runPeer(workspace, null)
                    check(runPeer(workspace, nativeId) == nativeId)
                    verifyScopedTool()
                    verifySdkStore()
                    verifyBundledAdapters()
                    PackagedBackendTurns.verify(targetContext)
                    PhysicalRuntimeProbe.verify(targetContext)
                }
                "ACP Android probe passed: fixture, tool revocation, interruption recovery and packaged adapter checks. App isolation: $support."
            }
        }
        val message = result.getOrElse(Throwable::stackTraceToString)
        finish(
            if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, message) },
        )
    }

    private suspend fun CoroutineScope.runPeer(workspace: File, nativeId: String?): String {
        val child = ProcessBuilder("/system/bin/sh", "-c", fixture)
            .directory(workspace)
            .redirectError(File(workspace, "probe-stderr"))
            .start()
        return AcpProcess(this, child).use { connection ->
            val session = connection.open(workspace, nativeId, emptyList()) { _, _ -> null }
            val text = StringBuilder()
            val reason = connection.prompt(session, "Read a synthetic note") { update ->
                val content = (update as? SessionUpdate.AgentMessageChunk)?.content as? ContentBlock.Text
                content?.let { text.append(it.text) }
            }
            check(reason == StopReason.END_TURN)
            check(text.toString() == "Device note")
            session.sessionId.value
        }
    }

    private fun CoroutineScope.verifyScopedTool() {
        val grant = AtomicBoolean(true)
        val executions = AtomicInteger()
        val schema = buildJsonObject { put("type", "object") }
        val tool = AgentTool("note", "A synthetic note", schema) {
            executions.incrementAndGet()
            buildJsonObject { put("text", "Device note") }
        }
        SampleMcpServer(this, listOf(tool)) { grant.get() }.use { endpoint ->
            val binding = endpoint.binding
            fun request(method: String, authenticated: Boolean = true): Pair<Int, JsonObject?> =
                Socket("127.0.0.1", binding.url.port).use { socket ->
                    socket.soTimeout = 3_000
                    val body = buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", 1); put("method", method)
                        put("params", buildJsonObject { put("name", "note") })
                    }.toString()
                    val authorization =
                        if (authenticated) "Authorization: ${binding.headers.getValue("Authorization")}\r\n" else ""
                    socket.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                        authorization + "Content-Length: ${body.length}\r\n\r\n" + body).toByteArray())
                    val reply = socket.getInputStream().bufferedReader().readText()
                    val status = reply.lineSequence().first().split(' ')[1].toInt()
                    val payload = reply.substringAfter("\r\n\r\n")
                    status to payload.takeIf { it.isNotBlank() }?.let {
                        Json.parseToJsonElement(it).jsonObject["result"]?.jsonObject
                    }
                }

            check(request("tools/call", authenticated = false).first == 401)
            check((request("tools/list").second?.get("tools") as JsonArray).size == 1)
            check(request("tools/call").second?.get("structuredContent") != null)
            grant.set(false)
            check((request("tools/list").second?.get("tools") as JsonArray).isEmpty())
            check(request("tools/call").second?.get("isError") == JsonPrimitive(true))
            check(executions.get() == 1)
        }
    }

    private fun verifySdkStore() {
        val directory = Files.createTempDirectory(targetContext.cacheDir.toPath(), "probe-store-").toFile()
        try {
            val persistence = object : AgentSessionPersistence {
                private var snapshot: String? = null
                override fun readSnapshot(): String? = snapshot
                override fun writeSnapshot(snapshot: String) { this.snapshot = snapshot }
            }
            val store = SessionStore(persistence)
            val session = SessionRecord(AgentSession("old", AgentBackend.Pi, "note"), nativeId = "old-peer",
                turns = listOf(AgentTurn("task", "Write a sample file", RunStatus.Running)))
            store.save(session)
            val recovered = SessionStore(persistence).all().single()
            check(recovered.nativeId == "old-peer")
            check(recovered.turns.single().status == RunStatus.Interrupted)
            check(SessionStore(persistence).all() == listOf(recovered))
            val workspace = File(directory, "workspace").apply { mkdir() }
            val artifact = File(workspace, "results.txt").apply { writeText("Keep this result.") }
            val temporary = File(directory, "task").apply { mkdir() }
            Files.createSymbolicLink(File(temporary, "workspace-link").toPath(), workspace.toPath())
            deleteAgentFiles(temporary)
            check(!temporary.exists() && artifact.readText() == "Keep this result.")
        } finally {
            directory.deleteRecursively()
        }
    }

    // Android's app UID can load the pinned runtime. This smoke check has empty state and runs no prompts.
    private suspend fun CoroutineScope.verifyBundledAdapters() {
        val packages = PackagedBackends(targetContext)
        val installed = packages.installed()
        val manifestPresent = runCatching { targetContext.assets.open("android-agent/versions.json").use { Unit } }.isSuccess
        if (!manifestPresent) return
        check(installed.isNotEmpty()) { "The APK's native runtimes were not extracted." }
        for ((backend, version) in installed) {
            val payload = packages.open(backend)
            val home = Files.createTempDirectory(targetContext.cacheDir.toPath(), "probe-adapter-").toFile()
            try {
                val child = ProcessBuilder(packages.node.absolutePath, payload.entry.absolutePath)
                    .directory(home)
                    .redirectError(File(home, "adapter.stderr"))
                    .apply {
                        environment().apply {
                            clear()
                            put("PATH", "/system/bin")
                            put("HOME", home.absolutePath)
                            put("TMPDIR", home.absolutePath)
                            put("CODEX_HOME", home.absolutePath)
                            put("CODEX_PATH", packages.codex.absolutePath)
                            put("NO_BROWSER", "1")
                        }
                    }.start()
                try {
                    AcpProcess(this, child).use { peer ->
                        val name = peer.identify()
                        check(name?.version == version) { "$backend returned unexpected metadata: $name" }
                        android.util.Log.i("AndroidAgentProbe", "Packaged $backend adapter $version initialized from app UID")
                    }
                } catch (error: Exception) {
                    // Probe credentials and state are synthetic. This output is never used by the host API.
                    val diagnostic = (error as? JsonRpcException)?.data
                    throw IllegalStateException("$backend probe failed: $diagnostic\n" +
                        File(home, "adapter.stderr").readText().takeLast(3000), error)
                }
            } finally {
                home.deleteRecursively()
            }
        }
    }

    private val fixture = """
        while IFS= read -r line; do
          id=$(printf '%s' "${'$'}line" | /system/bin/sed -n 's/.*"id"[ ]*:[ ]*\([0-9]*\).*/\1/p')
          case "${'$'}line" in
            *'"initialize"'*)
              printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}}\n' "${'$'}id"
              ;;
            *'"session/new"'*)
              printf '{"jsonrpc":"2.0","id":%s,"result":{"sessionId":"android-fixture"}}\n' "${'$'}id"
              ;;
            *'"session/load"'*'"android-fixture"'*)
              printf '{"jsonrpc":"2.0","id":%s,"result":{}}\n' "${'$'}id"
              ;;
            *'"session/prompt"'*)
              printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"android-fixture","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Device "}}}}'
              printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"android-fixture","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"note"}}}}'
              printf '{"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn"}}\n' "${'$'}id"
              ;;
          esac
        done
    """.trimIndent()
}
