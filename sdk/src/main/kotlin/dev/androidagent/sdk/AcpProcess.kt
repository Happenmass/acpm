package dev.androidagent.sdk

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class AcpToolCall(val title: String?, val hostToolName: String?)

/** ACP v1 session control over a process started by the Android execution layer. */
@OptIn(UnstableApi::class)
internal class AcpProcess(scope: CoroutineScope, private val child: Process) : Closeable {
    private val reader = child.inputStream.bufferedReader(Charsets.UTF_8)
    private val writer = child.outputStream.bufferedWriter(Charsets.UTF_8)
    private val transport = StdioTransport(
        parentScope = scope,
        ioDispatcher = Dispatchers.IO,
        input = flow {
            while (true) {
                val line = reader.readLine() ?: break
                emit(line)
            }
        },
        output = { line ->
            writer.write(line)
            writer.newLine()
            writer.flush()
        },
    )
    private val protocol = Protocol(scope, transport)
    private val client = Client(protocol)
    private val toolCalls = ConcurrentHashMap<ToolCallId, CompletableDeferred<AcpToolCall>>()

    init {
        protocol.start()
    }

    internal suspend fun identify() = withTimeout(20_000) {
        client.initialize(ClientInfo()).implementation
    }

    suspend fun open(
        workspace: File,
        nativeId: String?,
        servers: List<McpServer>,
        onPermission: suspend (AcpToolCall, List<PermissionOption>) -> PermissionOption?,
    ): ClientSession = withTimeout(20_000) {
        val agent = client.initialize(ClientInfo())
        val parameters = SessionCreationParameters(workspace.absolutePath, servers)
        val operations = object : ClientSessionOperations {
            override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
                recordToolCall(notification)
            }

            override suspend fun requestPermissions(
                toolCall: SessionUpdate.ToolCallUpdate,
                permissions: List<PermissionOption>,
                _meta: JsonElement?,
            ): RequestPermissionResponse {
                // Some adapters send a permission update with only the call identifier.
                val details = withTimeoutOrNull(300) {
                    toolCalls.computeIfAbsent(toolCall.toolCallId) { CompletableDeferred() }.await()
                }
                val call = AcpToolCall(toolCall.title?.takeIf { it.isNotBlank() } ?: details?.title,
                    details?.hostToolName)
                val decision = onPermission(call, permissions)?.takeIf { it in permissions }
                return RequestPermissionResponse(
                    decision?.let { RequestPermissionOutcome.Selected(it.optionId) }
                        ?: RequestPermissionOutcome.Cancelled,
                )
            }
        }
        if (nativeId == null) {
            client.newSession(parameters) { _, _ -> operations }
        } else {
            check(agent.capabilities.loadSession) { "The backend cannot restore this session." }
            client.loadSession(SessionId(nativeId), parameters, operations)
        }
    }

    suspend fun prompt(
        session: ClientSession,
        text: String,
        update: suspend (SessionUpdate) -> Unit,
    ): StopReason {
        var result: StopReason? = null
        session.prompt(listOf(ContentBlock.Text(text))).collect { event ->
            when (event) {
                is Event.SessionUpdateEvent -> {
                    recordToolCall(event.update)
                    update(event.update)
                }
                is Event.PromptResponseEvent -> result = event.response.stopReason
            }
        }
        return checkNotNull(result) { "The backend closed without a prompt result." }
    }

    private fun recordToolCall(update: SessionUpdate) {
        val (id, call) = when (update) {
            is SessionUpdate.ToolCall -> update.toolCallId to toolDetails(update.title, update.rawInput, update._meta)
            is SessionUpdate.ToolCallUpdate -> update.toolCallId to toolDetails(update.title, update.rawInput, update._meta)
            else -> return
        }
        if (!call.title.isNullOrBlank() || call.hostToolName != null) {
            toolCalls.computeIfAbsent(id) { CompletableDeferred() }.complete(call)
        }
    }

    private fun toolDetails(title: String?, rawInput: JsonElement?, metadata: JsonElement?): AcpToolCall {
        val input = rawInput as? JsonObject
        val name = if ((metadata as? JsonObject)?.get("is_mcp_tool_call") == JsonPrimitive(true) &&
            input?.get("server") == JsonPrimitive(HOST_MCP_SERVER_NAME)) {
            (input["tool"] as? JsonPrimitive)?.contentOrNull
        } else null
        return AcpToolCall(title, name)
    }

    override fun close() {
        // Kill the peer before closing Java streams. A blocked reader can hold its stream lock.
        child.destroy()
        if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly()
        protocol.close()
        child.outputStream.close()
        child.inputStream.close()
        child.errorStream.close()
    }
}
