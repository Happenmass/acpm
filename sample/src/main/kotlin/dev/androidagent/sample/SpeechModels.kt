package dev.androidagent.sample

import android.content.Context
import android.os.Build
import dev.androidagent.speech.HtpAsr
import dev.androidagent.speech.HtpAvailability
import dev.androidagent.speech.SpeakerDiarization
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** The service app owns all native model files and prepares its pinned QAIRT runtime. */
internal class SpeechModels(context: Context) {
    private val app = context.applicationContext
    val directory = File(app.filesDir, "speech")
    private val qwen = BundledSpeechWeights(app, "qwen", File(directory, "qwen"))
    private val speakerWeights = BundledSpeechWeights(app, "speakers", directory)
    val speakerDirectory get() = speakerWeights.directory
    fun asr(idleTimeoutMs: Int = 60 * 60 * 1000) =
        HtpAsr(app, File(directory, "qnn"), qwen.directory, idleTimeoutMs)
    val speakers get() = SpeakerDiarization(File(speakerDirectory, "segmentation.onnx"), File(speakerDirectory, "embedding.onnx"),
        if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.startsWith("SM8850")) 4 else 2)
    val speakerModelsAvailable get() = speakerWeights.bundled || speakers.available

    fun asrAvailability(): HtpAvailability {
        val status = asr().availability()
        return if (status == HtpAvailability.MODELS_NOT_INSTALLED && qwen.bundled) HtpAvailability.READY else status
    }

    suspend fun prepareAsr() {
        prepareRuntime()
        val state = asrAvailability()
        if (state != HtpAvailability.READY) throw dev.androidagent.speech.HtpUnavailableException(state)
        qwen.prepare()
    }

    suspend fun prepareSpeakers() = speakerWeights.prepare()

    fun prepareRuntime() = synchronized(extractionLock) {
        val manifest = runCatching {
            app.assets.open("qwen-htp-manifest.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("libraries") }
        }.getOrNull() ?: return@synchronized
        val dir = File(directory, "qnn")
        if (manifest.keys().asSequence().all { File(dir, it).length() == manifest.getLong(it) }) return@synchronized
        check(dir.isDirectory || dir.mkdirs())
        ZipInputStream(app.assets.open("qwen-htp-runtime.zip")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                check(manifest.has(name) && File(name).name == name)
                val dest = File(dir, name)
                val temp = File.createTempFile("library-", ".tmp", dir)
                try {
                    temp.outputStream().use { zip.copyTo(it) }
                    check(temp.length() == manifest.getLong(name) && temp.setReadOnly())
                    check(temp.renameTo(dest))
                } finally {
                    temp.delete()
                }
            }
        }
    }

    private companion object {
        val extractionLock = Any()
    }
}
