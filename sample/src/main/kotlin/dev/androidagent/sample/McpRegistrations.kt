package dev.androidagent.sample

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import dev.androidagent.client.McpTurnOutcome
import dev.androidagent.client.SpeechWire
import dev.androidagent.sdk.AgentBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class McpRegistration(
    val host: String,
    val backend: AgentBackend,
    val port: Int,
    val tools: List<String>,
    val outcome: McpTurnOutcome? = null,
    val mountedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
)

/** Process-local diagnostics. All mutations run on the main thread. */
internal object McpRegistrations {
    private data class Key(val uid: Int, val binder: IBinder, val id: String)
    private data class Active(val registration: McpRegistration, val death: IBinder.DeathRecipient)

    private val main = Handler(Looper.getMainLooper())
    private val active = mutableMapOf<Key, Active>()
    private val recent = ArrayDeque<McpRegistration>()
    private val snapshot = MutableStateFlow<List<McpRegistration>>(emptyList())
    val records = snapshot.asStateFlow()

    fun report(uid: Int, binder: IBinder, host: String, data: Bundle): Boolean {
        val id = data.getString(SpeechWire.MCP_ID)?.takeIf { it.length == 36 } ?: return false
        val key = Key(uid, binder, id)
        val outcome = data.getString(SpeechWire.MCP_OUTCOME)
        if (outcome != null) {
            finish(key, McpTurnOutcome.entries.firstOrNull { it.name == outcome } ?: return false)
            return true
        }
        if (key in active) return true
        if (active.size >= 16) return false
        val backend = AgentBackend.entries.firstOrNull { it.name == data.getString(SpeechWire.MCP_BACKEND) } ?: return false
        val port = data.getInt(SpeechWire.MCP_PORT)
        if (port !in 1024..65535) return false
        val tools = data.getStringArrayList(SpeechWire.MCP_TOOLS).orEmpty()
        if (tools.size > SpeechWire.MAX_REPORTED_TOOLS || tools.any { !SpeechWire.mcpToolName.matches(it) }) return false
        val death = IBinder.DeathRecipient { main.post { finish(key, McpTurnOutcome.Interrupted) } }
        try {
            binder.linkToDeath(death, 0)
        } catch (_: RemoteException) {
            return false
        }
        active[key] = Active(McpRegistration(host, backend, port, tools.distinct().sorted()), death)
        publish()
        return true
    }

    private fun finish(key: Key, outcome: McpTurnOutcome) {
        val item = active.remove(key) ?: return
        runCatching { key.binder.unlinkToDeath(item.death, 0) }
        recent.addFirst(item.registration.copy(outcome = outcome, endedAt = System.currentTimeMillis()))
        if (recent.size > 12) recent.removeLast()
        publish()
    }

    private fun publish() {
        snapshot.value = active.values.map { it.registration }.asReversed() + recent
    }
}
