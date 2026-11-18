package dev.androidagent.sample

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.androidagent.sdk.AgentTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Android host owns the URI grant. The agent receives a note ID and selected text. */
internal class SampleNotes(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val preferences = app.getSharedPreferences("sample-note", Context.MODE_PRIVATE)
    private val document: Uri?
        get() = preferences.getString("uri", null)?.let(Uri::parse)

    fun hasDocument(): Boolean = document?.let(::hasReadGrant) == true

    fun select(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val previous = document
        preferences.edit().putString("uri", uri.toString()).apply()
        previous?.takeUnless { it == uri }?.let(::release)
    }

    fun revoke() {
        val previous = document
        preferences.edit().remove("uri").apply()
        previous?.let(::release)
    }

    val readNote = AgentTool("read_note", "Read note demo or the explicitly selected note document", buildJsonObject {
        put("type", "object")
        put("required", JsonArray(listOf(JsonPrimitive("id"))))
        put("properties", buildJsonObject {
            put("id", buildJsonObject { put("type", "string") })
        })
    }) { args ->
        val text = when (args["id"]) {
            JsonPrimitive("demo") -> "The release codename in the local note is Kepler."
            JsonPrimitive("document") -> {
                val uri = requireNotNull(document) { "No document is selected." }
                check(hasReadGrant(uri)) { "Document access has been revoked." }
                requireNotNull(resolver.openInputStream(uri)).bufferedReader().use {
                    val buffer = CharArray(8193)
                    var total = 0
                    while (total < buffer.size) {
                        val count = it.read(buffer, total, buffer.size - total)
                        if (count == -1) break
                        total += count
                    }
                    check(total <= 8192) { "Select a note under 8192 characters." }
                    String(buffer, 0, total)
                }
            }
            else -> error("Unknown note ID.")
        }
        buildJsonObject { put("text", text) }
    }

    private fun hasReadGrant(uri: Uri) =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun release(uri: Uri) {
        if (hasReadGrant(uri)) resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
