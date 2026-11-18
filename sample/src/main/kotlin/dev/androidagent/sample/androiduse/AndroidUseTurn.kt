package dev.androidagent.sample.androiduse

import android.content.Context
import dev.androidagent.sample.SampleMcpServer
import dev.androidagent.sample.SampleMcpTool
import dev.androidagent.sdk.AgentBackend
import dev.androidagent.sdk.AgentRun
import dev.androidagent.sdk.AgentRuntime
import dev.androidagent.sdk.ApprovalKind
import dev.androidagent.sdk.ModelEndpoint
import dev.androidagent.sdk.RunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AndroidUseTurn(private val context: Context, private val scope: CoroutineScope) {
    var runId: String? = null
        private set

    suspend fun start(backend: AgentBackend, prompt: String, model: ModelEndpoint, target: String): AgentRun =
        withContext(Dispatchers.Main.immediate) {
            val runtime = AgentRuntime.get(context)
            val grant = AndroidUseSession.start(context, target, handoffs = true)
            var server: SampleMcpServer? = null
            try {
                val tools = AndroidUseSession.tools.map { tool ->
                    SampleMcpTool(tool.name, tool.description, tool.schema) { args -> grant.call(tool.name, args) }
                }
                server = SampleMcpServer(scope, emptyList(), "AI SDK Android Use", tools)
                val session = runtime.openSession(backend, "android-use")
                val run = runtime.submitWithHostMcp(session, "$prompt\n\n$verification", model.withImageInput(), server.binding)
                runId = run.id
                val approvals = approveAuthorizedTools(run, grant)
                scope.launch(Dispatchers.Main.immediate) {
                    try {
                        run.state.first { it.status != RunStatus.Running }
                    } finally {
                        grant.close()
                        server.close()
                        approvals.cancel()
                        if (runId == run.id) runId = null
                    }
                }
                run
            } catch (failure: Throwable) {
                grant.close()
                server?.close()
                throw failure
            }
        }

    private fun approveAuthorizedTools(run: AgentRun, grant: AndroidUseSession): Job = scope.launch(Dispatchers.Main.immediate) {
        run.approval.filterNotNull().collect { request ->
            val allowed = grant.isGranted && request.hostToolName in AndroidUseSession.toolNames
            val option = request.options.firstOrNull { allowed && it.kind == ApprovalKind.ALLOW_ONCE }
            run.respondToApproval(request.id, option?.id)
        }
    }

    private companion object {
        val verification = """
            Reuse an action's returned observation and snapshot ID. Call android_observe when you need an image or newer state.
            An accepted action confirms dispatch. Before reporting success, compare every requested value with the final UI,
            including each component of a value such as hour, minute and AM or PM. Correct a mismatch or report the difference.
        """.trimIndent()
    }
}
