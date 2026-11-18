package dev.androidagent.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable snapshot storage provided by the Android host. Writes must be atomic. */
interface AgentSessionPersistence {
    fun readSnapshot(): String?
    fun writeSnapshot(snapshot: String)
}

@Serializable
internal data class SessionRecord(
    val session: AgentSession,
    val nativeId: String? = null,
    val modelIdentity: String? = null,
    val turns: List<AgentTurn> = emptyList(),
)

internal class SessionStore(private val persistence: AgentSessionPersistence?) {
    private val json = Json { ignoreUnknownKeys = true }
    private var current = persistence?.readSnapshot()?.let { json.decodeFromString<List<SessionRecord>>(it) }.orEmpty()

    init {
        if (current.any { session -> session.turns.any { it.status == RunStatus.Running } }) {
            save(current.map { record ->
                record.copy(turns = record.turns.map { turn ->
                    if (turn.status == RunStatus.Running) turn.copy(status = RunStatus.Interrupted, activeTool = null)
                    else turn
                })
            })
        }
    }

    fun all(): List<SessionRecord> = current

    fun save(record: SessionRecord) {
        replace(record.session.id, record)
    }

    fun replace(sessionId: String, record: SessionRecord) {
        save(current.filterNot { it.session.id == sessionId } + record)
    }

    fun delete(sessionId: String) {
        save(current.filterNot { it.session.id == sessionId })
    }

    private fun save(records: List<SessionRecord>) {
        persistence?.writeSnapshot(json.encodeToString(records))
        current = records
    }
}
