package dev.androidagent.sdk

import android.content.Context
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Application-scoped owner of ACP sessions and local runs. An Activity can resubscribe after recreation. */
class AgentRuntime private constructor(
    context: Context,
    private val persistence: AgentSessionPersistence?,
    filesDirectory: File?,
) {
    private val app = context.applicationContext
    private val root = filesDirectory ?: File(app.cacheDir, "android-agent")
    private val store by lazy {
        val loaded = SessionStore(persistence)
        // A force-stop skips coroutine cleanup. The single application runtime can
        // remove its own abandoned scratch directories before accepting more work.
        File(app.cacheDir, "android-agent-tasks").listFiles()?.forEach { path -> deleteAgentFiles(path) }
        loaded
    }
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val backends = PackagedBackends(app)
    private val driver = LocalAgentDriver(app, backends)
    private val _activeRun = MutableStateFlow<AgentRun?>(null)
    val activeRun: StateFlow<AgentRun?> = _activeRun.asStateFlow()
    private var active: Execution? = null

    fun installedBackends(): List<InstalledBackend> = backends.installed()

    /** Aggregates the host's session history without returning prompts, tool arguments or credentials. */
    suspend fun activity(): List<AgentActivity> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val running = active
            val sessions = store.all().groupBy { it.session.backend }
            AgentBackend.entries.map { backend ->
                val records = sessions[backend].orEmpty()
                val turns = records.flatMap { it.turns }
                val current = running?.takeIf { it.session.backend == backend }?.state?.value
                val observed = turns.map { if (it.id == current?.id) current else it }
                val tools = observed.flatMap { it.toolCalls.entries }.groupingBy { it.key }.fold(0) { sum, item -> sum + item.value }
                AgentActivity(backend, records.size, turns.size, current != null,
                    current != null && running?.approval?.value != null, current?.status ?: turns.lastOrNull()?.status,
                    current?.contextUsage ?: turns.lastOrNull { it.contextUsage != null }?.contextUsage,
                    tools, observed.sumOf { it.imageOutputs })
            }
        }
    }

    // Stable identifiers, not paths, preserve the workspace across sessions and backend changes.
    fun workspace(workspace: String): File {
        require(workspace.isNotBlank())
        return File(File(root, "workspaces"), digest(workspace))
    }

    /** Register only for tasks with an explicit workspace-code grant from the host. */
    @JvmOverloads
    fun workspaceScriptTool(workspace: String, workspaceVariable: String = "ANDROID_AGENT_WORKSPACE"): AgentTool {
        require(workspaceVariable.matches(Regex("[A-Z][A-Z0-9_]*_WORKSPACE")))
        return WorkspaceScripts(app, backends.node).tool(workspace(workspace), workspaceVariable)
    }

    suspend fun openSession(backend: AgentBackend, workspace: String): AgentSession = withContext(Dispatchers.IO) {
        require(workspace.isNotBlank())
        synchronized(lock) {
            val session = AgentSession(UUID.randomUUID().toString(), backend, workspace)
            store.save(SessionRecord(session))
            session
        }
    }

    suspend fun sessions(): List<AgentSession> = withContext(Dispatchers.IO) {
        synchronized(lock) { store.all().map(SessionRecord::session) }
    }

    suspend fun history(session: AgentSession): List<AgentTurn> = withContext(Dispatchers.IO) {
        synchronized(lock) { record(session).turns }
    }

    /** Delete the logical session and backend state while preserving shared workspace files. */
    suspend fun deleteSession(session: AgentSession) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (active?.session == session) throw AgentBusyException()
            record(session)
            val home = sessionHome(session)
            if (home.exists()) deleteAgentFiles(home)
            store.delete(session.id)
        }
    }

    /** Explicitly remove workspace files. Sessions and their history retain their own lifetime. */
    suspend fun deleteWorkspace(workspace: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (active?.session?.workspace == workspace) throw AgentBusyException()
            val directory = workspace(workspace)
            if (directory.exists()) deleteAgentFiles(directory)
        }
    }

    /** Create a fresh backend session while preserving the app-owned workspace files. */
    suspend fun clearSession(session: AgentSession): AgentSession = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (active?.session == session) throw AgentBusyException()
            record(session)
            val home = sessionHome(session)
            if (home.exists()) deleteAgentFiles(home)
            val replacement = session.copy(id = UUID.randomUUID().toString())
            store.replace(session.id, SessionRecord(replacement))
            replacement
        }
    }

    suspend fun submit(
        session: AgentSession,
        prompt: String,
        model: ModelEndpoint,
    ): AgentRun = submitTurn(session, prompt, model, hostMcp = null)

    /** The host starts and closes the MCP server and authorizes its tools. */
    suspend fun submitWithHostMcp(
        session: AgentSession,
        prompt: String,
        model: ModelEndpoint,
        hostMcp: AgentMcpServer,
    ): AgentRun = submitTurn(session, prompt, model, hostMcp)

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private suspend fun submitTurn(
        session: AgentSession,
        prompt: String,
        model: ModelEndpoint,
        hostMcp: AgentMcpServer?,
    ): AgentRun = withContext(Dispatchers.IO) {
        val endpoint = requireNotNull(model.baseUrl.toHttpUrlOrNull()) { "Enter a valid model base URL." }
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty() &&
            endpoint.query == null && endpoint.fragment == null && model.model.isNotBlank()) {
            "Enter a model identifier and a base URL without embedded credentials, query, or fragment."
        }
        require(prompt.isNotBlank()) { "Enter a task." }
        val modelIdentity = digest(endpoint.toString() + "\n" + model.model)
        synchronized(lock) {
            if (active != null) throw AgentBusyException()
            val record = record(session)
            require(record.modelIdentity == null || record.modelIdentity == modelIdentity) {
                "Open a new session to use a different model or base URL."
            }
            val start = AgentTurn(UUID.randomUUID().toString(), prompt, RunStatus.Running)
            store.save(record.copy(turns = record.turns + start))
            val execution = Execution(session, MutableStateFlow(start))
            val handle = AgentRun(start.id, execution.state.asStateFlow(), execution.approval.asStateFlow(),
                requestCancellation = { cancel(execution) },
                approvalResponse = { id, option -> approve(execution, id, option) })
            active = execution
            _activeRun.value = handle
            // The terminal state must be saved even if cancellation wins before dispatch.
            execution.job = worker.launch(start = CoroutineStart.ATOMIC) {
                val (status, message) = try {
                    val result = driver.run(record, prompt, model, workspace(session.workspace), sessionHome(session),
                        approve = { tool, options ->
                            val request = ApprovalRequest(UUID.randomUUID().toString(), tool.title ?: "Agent operation", options.map {
                                ApprovalOption(it.optionId.value, it.name, ApprovalKind.valueOf(it.kind.name))
                            }, tool.hostToolName?.takeIf { name -> hostMcp?.toolNames?.contains(name) == true })
                            val selected = awaitApproval(execution, request)
                            options.firstOrNull { it.optionId.value == selected }
                        },
                        nativeSession = { id ->
                            synchronized(lock) {
                                val saved = record(session)
                                store.save(saved.copy(nativeId = id, modelIdentity = modelIdentity))
                            }
                        },
                        update = { update(execution, it) },
                        hostMcp = hostMcp,
                    )
                    when (result) {
                        StopReason.CANCELLED -> RunStatus.Cancelled to null
                        StopReason.END_TURN -> RunStatus.Succeeded to null
                        else -> RunStatus.Failed to "The backend ended the turn early (${result.name.lowercase()})."
                    }
                } catch (_: TimeoutCancellationException) {
                    RunStatus.Failed to "The backend did not finish connecting in time."
                } catch (_: CancellationException) {
                    if (currentCoroutineContext().isActive) {
                        RunStatus.Failed to "The local backend closed before producing a result."
                    } else RunStatus.Cancelled to null
                } catch (device: UnsupportedAgentDevice) {
                    RunStatus.Failed to "Local agents require Landlock from the Android app (${device.support.javaClass.simpleName})."
                } catch (failure: ModelServiceException) {
                    RunStatus.Failed to "The model service request failed (HTTP ${failure.statusCode})."
                } catch (_: Exception) {
                    RunStatus.Failed to "The local backend could not complete the task. Check the runtime pack and model configuration."
                }
                finish(execution, status, message)
            }
            handle
        }
    }

    private suspend fun awaitApproval(execution: Execution, request: ApprovalRequest): String? {
        val waiting = synchronized(lock) {
            if (execution.cancelRequested || execution.approval.value != null) return null
            CompletableDeferred<String?>().also {
                execution.approvalWaiter = it
                execution.approval.value = request
            }
        }
        return try {
            waiting.await()
        } finally {
            synchronized(lock) {
                execution.approvalWaiter = null
                execution.approval.value = null
            }
        }
    }

    private fun approve(execution: Execution, id: String, option: String?): Boolean = synchronized(lock) {
        val request = execution.approval.value
        if (active !== execution || execution.cancelRequested || request?.id != id) return false
        if (option != null && request.options.none { it.id == option }) return false
        execution.approvalWaiter?.complete(option) ?: false
    }

    @OptIn(UnstableApi::class)
    private fun update(execution: Execution, update: SessionUpdate) {
        val state = execution.state
        val turn = state.value
        when (update) {
            is SessionUpdate.AgentMessageChunk -> {
                if (update.content is ContentBlock.Image) {
                    state.value = turn.copy(imageOutputs = turn.imageOutputs + 1)
                    return
                }
                val text = (update.content as? ContentBlock.Text)?.text ?: return
                if (text.isEmpty()) return
                val messageId = update.messageId?.value
                val previousId = execution.answerMessageId
                val latestMessage = when {
                    messageId != null && messageId != previousId -> text
                    messageId == null && previousId != null -> turn.latestMessage
                    else -> turn.latestMessage + text
                }
                if (messageId != null) execution.answerMessageId = messageId
                state.value = turn.copy(answer = turn.answer + text, latestMessage = latestMessage)
            }
            is SessionUpdate.ToolCall -> {
                val kind = update.kind?.name ?: "Other"
                val counts = if (execution.toolIds.add(update.toolCallId.value))
                    turn.toolCalls + (kind to ((turn.toolCalls[kind] ?: 0) + 1)) else turn.toolCalls
                state.value = turn.copy(activeTool = update.title, toolCalls = counts)
            }
            is SessionUpdate.ToolCallUpdate -> if (update.title != null) {
                state.value = turn.copy(activeTool = update.title)
            }
            is SessionUpdate.UsageUpdate -> state.value = turn.copy(
                contextUsage = AgentContextUsage(update.used, update.size),
            )
            else -> Unit
        }
    }

    private fun finish(execution: Execution, status: RunStatus, message: String?) = synchronized(lock) {
        val final = execution.state.value.copy(
            status = if (execution.cancelRequested) RunStatus.Cancelled else status,
            activeTool = null,
            message = if (execution.cancelRequested) null else message,
        )
        try {
            val record = record(execution.session)
            store.save(record.copy(turns = record.turns.map { if (it.id == final.id) final else it }))
            execution.state.value = final
        } catch (_: Exception) {
            execution.state.value = final.copy(
                status = RunStatus.Failed, message = "The task outcome could not be saved.",
            )
        } finally {
            active = null
            _activeRun.value = null
        }
    }

    private fun cancel(execution: Execution) = synchronized(lock) {
        if (active === execution) {
            execution.cancelRequested = true
            execution.job?.cancel()
        }
    }

    private fun sessionHome(session: AgentSession) = File(File(root, "backend-sessions"), session.id)

    private fun record(session: AgentSession) = requireNotNull(store.all().find { it.session == session }) {
        "The session does not exist in this runtime."
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private class Execution(val session: AgentSession, val state: MutableStateFlow<AgentTurn>) {
        val toolIds = HashSet<String>()
        var answerMessageId: String? = null
        var job: Job? = null
        var cancelRequested: Boolean = false
        val approval = MutableStateFlow<ApprovalRequest?>(null)
        var approvalWaiter: CompletableDeferred<String?>? = null
    }

    companion object {
        private var instance: AgentRuntime? = null

        /** Supply host storage on the first application-scoped lookup. */
        @Synchronized
        @JvmOverloads
        fun get(
            context: Context,
            persistence: AgentSessionPersistence? = null,
            filesDirectory: File? = null,
        ): AgentRuntime {
            val existing = instance
            if (existing != null) {
                check(persistence == null || existing.persistence === persistence) {
                    "Configure the Agent host before the first lookup."
                }
                check(filesDirectory == null || existing.root == filesDirectory) {
                    "Agent files directory is already configured."
                }
                return existing
            }
            return AgentRuntime(context, persistence, filesDirectory).also { instance = it }
        }
    }
}
