package dev.androidagent.sdk

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

enum class AgentBackend(internal val key: String) {
    Pi("pi"), Codex("codex");
}

data class InstalledBackend(val backend: AgentBackend, val version: String)

/** Discover the runtime packs embedded by the Android application build. */
class PackagedBackends(context: Context) {
    private val app = context.applicationContext
    private val nativeDir = File(app.applicationInfo.nativeLibraryDir)
    internal val node = File(nativeDir, "libandroid_agent_node.so")
    internal val codex = File(nativeDir, "libandroid_agent_codex.so")

    private val manifest: JsonObject? by lazy {
        try {
            app.assets.open("android-agent/versions.json").bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonObject
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    fun installed(): List<InstalledBackend> = AgentBackend.entries.mapNotNull { backend ->
        val adapter = adapter(backend) ?: return@mapNotNull null
        if (!node.isFile || backend == AgentBackend.Codex && !codex.isFile) return@mapNotNull null
        InstalledBackend(backend, adapter.string("version"))
    }

    private fun adapter(backend: AgentBackend): JsonObject? =
        (manifest?.get("adapters") as? JsonObject)?.get(backend.key) as? JsonObject

    internal fun open(backend: AgentBackend): StagedAdapter {
        val entry = requireNotNull(adapter(backend)) { "The selected backend pack is absent." }.string("entry")
        check(node.isFile && (backend != AgentBackend.Codex || codex.isFile))
        val expected = requireNotNull(manifest).string("sha256")
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val parent = File(app.codeCacheDir, "android-agent-runtimes").apply { mkdirs() }
        val directory = File(parent, expected)
        synchronized(extractionLock) {
            if (!File(directory, entry).isFile) {
                val staging = File(parent, ".extract-${UUID.randomUUID()}").apply { check(mkdir()) }
                try {
                    checkedExtraction(staging, expected)
                    check(File(staging, entry).isFile)
                    check(staging.renameTo(directory) ||
                        File(directory, entry).isFile)
                } finally {
                    if (staging.exists()) staging.deleteRecursively()
                }
            }
        }
        return StagedAdapter(directory, File(directory, entry))
    }

    private fun checkedExtraction(staging: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        app.assets.open("android-agent/backend.zip").use { raw ->
            DigestInputStream(raw, digest).use { input ->
                val zip = ZipInputStream(input)
                while (true) {
                    val file = zip.nextEntry ?: break
                    val target = File(staging, file.name).canonicalFile
                    check(target.path.startsWith(staging.canonicalPath + File.separator))
                    if (file.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
                // Consume the ZIP directory and remaining bytes for the source digest.
                val buffer = ByteArray(8192)
                while (input.read(buffer) != -1) Unit
                zip.close()
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { "The bundled runtime digest does not match its manifest." }
    }

    private fun JsonObject.string(name: String): String = (getValue(name) as JsonPrimitive).content

    companion object {
        private val extractionLock = Any()
    }
}

internal data class StagedAdapter(val directory: File, val entry: File)
