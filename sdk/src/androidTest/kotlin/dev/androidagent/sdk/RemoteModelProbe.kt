package dev.androidagent.sdk

import dev.androidagent.sample.SampleMcpServer
import android.content.Context
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Optional SDK acceptance against a caller-configured loopback model gateway. */
internal object RemoteModelProbe {
    suspend fun run(context: Context, arguments: Bundle): String = coroutineScope {
        check(DeviceSupport.check(context) is DeviceSupport.Supported)
        val backend = when (arguments.getString("backend")) {
            "pi" -> AgentBackend.Pi
            "codex" -> AgentBackend.Codex
            else -> error("Provide -e backend pi or codex.")
        }
        val port = requireNotNull(arguments.getString("gatewayPort")?.toIntOrNull()) { "Provide the ADB-reversed gateway port." }
        require(port in 1..65535)
        val host = arguments.getString("gatewayHost") ?: "127.0.0.1"
        require(host in setOf("127.0.0.1", "10.0.2.2"))
        val model = requireNotNull(arguments.getString("model")) { "Provide the model id." }
        val runtime = AgentRuntime.get(context)
        check(runtime.installedBackends().any { it.backend == backend })
        val scopeId = "remote-probe-${UUID.randomUUID()}"
        val session = runtime.openSession(backend, scopeId)
        val marker = "note-${UUID.randomUUID()}"
        val calls = AtomicInteger()
        val note = AgentTool("read_note", "Read one synthetic fixture note by id. It contains the required marker.",
            buildJsonObject {
                put("type", "object")
                put("required", JsonArray(listOf(JsonPrimitive("id"))))
                put("properties", buildJsonObject {
                    put("id", buildJsonObject { put("type", "string") })
                })
            }) { arguments ->
            check(arguments["id"] == JsonPrimitive("fixture"))
            calls.incrementAndGet()
            buildJsonObject { put("marker", marker) }
        }
        val mcp = SampleMcpServer(this, listOf(note))
        val turn = runtime.submitWithHostMcp(session,
            "This is a one-tool SDK check. Discover the read_note tool if necessary, call it with id fixture, and reply with its marker. " +
                "Use the host tool. Do not run a command or read files.",
            ModelEndpoint("http://$host:$port/v1/", model), mcp.binding)
        val approvals = launch {
            turn.approval.filterNotNull().collect { request ->
                val option = request.options.firstOrNull {
                    it.kind == ApprovalKind.ALLOW_ONCE && request.hostToolName == "read_note"
                }
                turn.respondToApproval(request.id, option?.id)
            }
        }
        try {
            val result = withTimeout(65_000) { turn.state.first { it.status != RunStatus.Running } }
            check(result.status == RunStatus.Succeeded) { "$backend did not finish: ${result.message}" }
            check(calls.get() == 1 && marker in result.latestMessage) {
                "$backend completed with ${calls.get()} successful Kotlin tool calls. Marker returned: ${marker in result.latestMessage}."
            }
            val usage = result.contextUsage?.let { " Context tokens ${it.usedTokens}/${it.limitTokens}." }.orEmpty()
            "Remote model probe passed: $backend discovered and called the Kotlin tool and returned its marker.$usage"
        } finally {
            if (turn.state.value.status == RunStatus.Running) {
                turn.cancel()
                withTimeout(5000) { turn.state.first { it.status != RunStatus.Running } }
            }
            approvals.cancelAndJoin()
            mcp.close()
            runtime.deleteSession(session)
            runtime.deleteWorkspace(scopeId)
        }
    }
}
