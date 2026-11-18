package dev.androidagent.sdk

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
@ConsistentCopyVisibility
data class AgentSession internal constructor(
    val id: String,
    val backend: AgentBackend,
    val workspace: String,
)

/** The host may keep this object in memory. Its credential is never written by the SDK. */
class ModelEndpoint private constructor(
    val baseUrl: String,
    val model: String,
    internal val apiKey: String?,
    internal val acceptsImages: Boolean,
) {
    constructor(baseUrl: String, model: String, apiKey: String? = null) : this(baseUrl, model, apiKey, false)

    /** Declare that this model can consume images returned by tools. */
    fun withImageInput(): ModelEndpoint = ModelEndpoint(baseUrl, model, apiKey, true)
}

enum class RunStatus { Running, Succeeded, Cancelled, Failed, Interrupted }

/** The adapter's latest reported context use and limit, in tokens. */
@Serializable
data class AgentContextUsage(val usedTokens: Long, val limitTokens: Long)

@Serializable
data class AgentTurn(
    val id: String,
    val prompt: String,
    val status: RunStatus,
    val answer: String = "",
    val activeTool: String? = null,
    val message: String? = null,
    val contextUsage: AgentContextUsage? = null,
    /** The latest assistant message; [answer] contains the entire streamed transcript. */
    val latestMessage: String = "",
    val toolCalls: Map<String, Int> = emptyMap(),
    val imageOutputs: Int = 0,
)

data class AgentActivity(
    val backend: AgentBackend,
    val sessions: Int,
    val turns: Int,
    val running: Boolean,
    val awaitingApproval: Boolean,
    val lastStatus: RunStatus?,
    val context: AgentContextUsage?,
    val tools: Map<String, Int>,
    val images: Int,
)

enum class ApprovalKind { ALLOW_ONCE, ALLOW_ALWAYS, REJECT_ONCE, REJECT_ALWAYS }

data class ApprovalOption(val id: String, val label: String, val kind: ApprovalKind)
data class ApprovalRequest(
    val id: String,
    val title: String,
    val options: List<ApprovalOption>,
    /** The correlated MCP tool name when the call targets the Android host's registered tools. */
    val hostToolName: String? = null,
)

class AgentRun internal constructor(
    val id: String,
    val state: StateFlow<AgentTurn>,
    val approval: StateFlow<ApprovalRequest?>,
    private val requestCancellation: () -> Unit,
    private val approvalResponse: (String, String?) -> Boolean,
) {
    fun cancel() = requestCancellation()
    fun respondToApproval(requestId: String, optionId: String?): Boolean = approvalResponse(requestId, optionId)
}

class AgentBusyException : IllegalStateException("Another task is already running.")
class UnsupportedAgentDevice(val support: DeviceSupport) : IllegalStateException("Local agent isolation is unavailable.")
