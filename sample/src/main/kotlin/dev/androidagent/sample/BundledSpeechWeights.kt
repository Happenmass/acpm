package dev.androidagent.sample

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Keeps the APK's verified weights in versioned private directories for native access. */
internal class BundledSpeechWeights(context: Context, private val group: String, private val developmentDirectory: File) {
    private val app = context.applicationContext
    private val manifest by lazy {
        runCatching {
            app.assets.open("speech-models/manifest.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject(group) }
        }.getOrNull()
    }
    private val installed by lazy {
        manifest?.let { File(app.noBackupFilesDir, "speech-models/$group-${it.getString("revision")}") }
    }
    val bundled get() = installed != null
    val directory get() = installed ?: developmentDirectory

    suspend fun prepare() {
        val metadata = manifest ?: return
        val target = requireNotNull(installed)
        mutex.withLock {
            val files = metadata.getJSONObject("files")
            val names = files.keys().asSequence().toList()
            if (complete(target, files, names)) return@withLock
            val parent = requireNotNull(target.parentFile)
            check(parent.isDirectory || parent.mkdirs())
            require(parent.usableSpace >= names.sumOf { files.getJSONArray(it).getLong(0) }) {
                "Not enough space for the speech models"
            }
            val staging = File(parent, target.name + ".staging")
            check(staging.deleteRecursively() && staging.mkdir())
            try {
                val buffer = ByteArray(256 * 1024)
                for (name in names) {
                    check(File(name).name == name)
                    val entry = files.getJSONArray(name)
                    val sha = MessageDigest.getInstance("SHA-256")
                    val file = File(staging, name)
                    app.assets.open("speech-models/$group/$name", AssetManager.ACCESS_STREAMING).use { input ->
                        file.outputStream().use { output ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val bytes = input.read(buffer)
                                if (bytes < 0) break
                                output.write(buffer, 0, bytes)
                                sha.update(buffer, 0, bytes)
                            }
                            output.fd.sync()
                        }
                    }
                    check(file.length() == entry.getLong(0) &&
                        sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == entry.getString(1))
                }
                currentCoroutineContext().ensureActive()
                check(target.deleteRecursively() && staging.renameTo(target))
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    private fun complete(target: File, files: JSONObject, names: List<String>) = names.all {
        val file = File(target, it)
        file.isFile && file.length() == files.getJSONArray(it).getLong(0)
    }

    private companion object {
        val mutex = Mutex()
    }
}
