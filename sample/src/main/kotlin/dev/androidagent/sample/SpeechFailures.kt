package dev.androidagent.sample

import android.content.Context
import android.os.Bundle
import android.util.Log
import dev.androidagent.client.SpeechWire
import org.json.JSONArray
import org.json.JSONObject

internal data class SpeechFailure(
    val atMs: Long,
    val elapsedMs: Long,
    val requestId: String?,
    val caller: String,
    val operation: String,
    val stage: String,
    val reason: String,
    val detail: String,
    val nativeCode: String?,
    val servicePid: Int,
) {
    fun reply() = Bundle().apply {
        putString(SpeechWire.ERROR, reason)
        putString(SpeechWire.FAILURE_STAGE, stage)
        putString(SpeechWire.FAILURE_DETAIL, detail)
        putString(SpeechWire.NATIVE_ERROR_CODE, nativeCode)
        putLong(SpeechWire.FAILURE_ELAPSED_MS, elapsedMs)
    }

    fun json() = JSONObject().apply {
        put("atMs", atMs)
        put("elapsedMs", elapsedMs)
        put("requestId", requestId)
        put("caller", caller)
        put("operation", operation)
        put("stage", stage)
        put("reason", reason)
        put("detail", detail)
        put("nativeCode", nativeCode)
        put("servicePid", servicePid)
    }

    companion object {
        fun fromJson(row: JSONObject) = SpeechFailure(row.getLong("atMs"), row.getLong("elapsedMs"),
            row.optString("requestId").ifEmpty { null }, row.getString("caller"), row.getString("operation"),
            row.getString("stage"), row.getString("reason"), row.getString("detail"),
            row.optString("nativeCode").ifEmpty { null }, row.optInt("servicePid"))
    }
}

/** App-private, bounded failure history shared by the provider and its console. */
internal class SpeechFailures(context: Context) {
    private val preferences = context.getSharedPreferences("speech_failures", Context.MODE_PRIVATE)

    fun recent(): List<SpeechFailure> {
        val rows = runCatching { JSONArray(preferences.getString("recent", "[]")) }.getOrElse { JSONArray() }
        return (0 until minOf(rows.length(), LIMIT)).mapNotNull { index ->
            runCatching { SpeechFailure.fromJson(rows.getJSONObject(index)) }.getOrNull()
        }
    }

    fun record(failure: SpeechFailure) {
        val saved = synchronized(lock) {
            val updated = listOf(failure) + recent().take(LIMIT - 1)
            preferences.edit().putString("recent", JSONArray(updated.map(SpeechFailure::json)).toString()).commit()
        }
        if (!saved) Log.w("AiSpeechService", "Unable to persist speech failure history")
        Log.w("AiSpeechService", "caller=${failure.caller} operation=${failure.operation} " +
            "request=${failure.requestId ?: "-"} stage=${failure.stage} reason=${failure.reason} " +
            "detail=${failure.detail} nativeCode=${failure.nativeCode ?: "-"} elapsedMs=${failure.elapsedMs} pid=${failure.servicePid}")
    }

    private companion object {
        const val LIMIT = 20
        val lock = Any()
    }
}
