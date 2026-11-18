package dev.androidagent.speech

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

enum class HtpAvailability { READY, UNSUPPORTED_DEVICE, RUNTIME_NOT_INSTALLED, MODELS_NOT_INSTALLED }

enum class HtpServiceState { STOPPED, LOADING, RUNNING, WARM, UNLOADED }

data class HtpServiceStatus(
    val state: HtpServiceState = HtpServiceState.STOPPED,
    val memoryRssKiB: Long? = null,
    val peakRssKiB: Long? = null,
    val completedWindows: Int = 0,
    val lastDecodeMs: Double? = null,
)

class HtpUnavailableException(val reason: HtpAvailability) : IllegalStateException("Qualcomm HTP unavailable ($reason).")

data class HtpWindowTiming(
    val melMs: Double,
    val encoderMs: Double,
    val prefillMs: Double,
    val decodeMs: Double,
    val tokens: Int,
    val decodeGraphMs: Double = 0.0,
    val decodeKvMs: Double = 0.0,
    val decodeCalls: Int = 0,
    val workerPeakRssKiB: Long = 0,
) {
    val decodeOtherMs: Double get() = (decodeMs - decodeGraphMs - decodeKvMs).coerceAtLeast(0.0)
}

/** The range is the recognition window. Qwen's text is not word-aligned. */
data class HtpTranscriptSegment(val range: SpeechRange, val text: String, val timing: HtpWindowTiming)

data class HtpTranscript(
    val segments: List<HtpTranscriptSegment>,
    /** Total model load time for this call, zero for reuse, null with no executed windows. */
    val modelLoadMs: Double?,
) {
    val text: String get() = segments.joinToString("\n") { it.text }.trim()
    val executedWindows: Int get() = segments.size
}

/** Stateless transcription through an app-process-scoped QNN server. */
class HtpAsr @JvmOverloads constructor(
    context: Context,
    private val runtimeDirectory: File,
    private val modelDirectory: File,
    modelIdleTimeoutMs: Int = 60 * 60 * 1000,
) {
    private val app = context.applicationContext
    private val worker = File(app.applicationInfo.nativeLibraryDir, "libandroid_ai_qwen_htp.so")
    private val modelIdleTimeoutMs = modelIdleTimeoutMs.also { require(it > 0) }

    fun availability(): HtpAvailability {
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a") ||
            Build.VERSION.SDK_INT < 31 ||
            Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Os.sysconf(OsConstants._SC_PAGESIZE) != 4096L
        ) return HtpAvailability.UNSUPPORTED_DEVICE
        if (!worker.canExecute()) return HtpAvailability.RUNTIME_NOT_INSTALLED
        val manifest = runCatching {
            JSONObject(app.assets.open("qwen-htp-manifest.json").bufferedReader().use { it.readText() })
        }.getOrElse { return HtpAvailability.RUNTIME_NOT_INSTALLED }
        val supported = manifest.optJSONArray("socModels")
        val compatibleSoc = if (supported == null) Build.SOC_MODEL.startsWith("SM8850", ignoreCase = true)
            else (0 until supported.length()).any {
                val soc = supported.getString(it)
                Build.SOC_MODEL.equals(soc, ignoreCase = true) || Build.SOC_MODEL.startsWith("$soc-", ignoreCase = true)
            }
        val genericArch = manifest.optInt("htpArch")
        if (!compatibleSoc && genericArch == 0) return HtpAvailability.UNSUPPORTED_DEVICE
        if (!complete(runtimeDirectory, manifest.getJSONObject("libraries"))) {
            return HtpAvailability.RUNTIME_NOT_INSTALLED
        }
        if (!compatibleSoc && detectedArch(runtimeDirectory, worker) != genericArch) return HtpAvailability.UNSUPPORTED_DEVICE
        if (!complete(modelDirectory, manifest.getJSONObject("models"))) return HtpAvailability.MODELS_NOT_INSTALLED
        return HtpAvailability.READY
    }

    suspend fun transcribe(
        audio: File,
        ranges: List<SpeechRange>? = null,
        onProgress: (Float) -> Unit = {},
    ): HtpTranscript = withContext(Dispatchers.IO) {
        requireAvailable()
        val wav = audio.openSpeechAudio()
        val selected = ranges ?: listOf(SpeechRange(0, wav.sampleCount))
        selected.forEach(wav::checkRange)
        val totalSamples = selected.sumOf { it.endSample - it.startSample }
        if (totalSamples == 0L) return@withContext HtpTranscript(emptyList(), null)
        createDecoder().use { decoder ->
            var completedSamples = 0L
            val segments = mutableListOf<HtpTranscriptSegment>()
            for (range in selected) {
                var start = range.startSample
                while (start < range.endSample) {
                    currentCoroutineContext().ensureActive()
                    val end = minOf(start + WINDOW_SAMPLES, range.endSample)
                    val samples = wav.readMono(start, (end - start).toInt())
                    decoder.decode(samples)?.let { recognized ->
                        segments += HtpTranscriptSegment(SpeechRange(start, end), recognized.text, recognized.timing)
                    }
                    completedSamples += end - start
                    onProgress(completedSamples.toFloat() / totalSamples)
                    start = end
                }
            }
            HtpTranscript(segments, decoder.modelLoadMs)
        }
    }

    /** Short-lived decoder for a caller that already owns window planning or checkpoints. */
    fun openDecoder(): Decoder {
        requireAvailable()
        return createDecoder()
    }

    private fun createDecoder(): Decoder {
        val configuration = WorkerConfiguration(worker, runtimeDirectory.canonicalFile, modelDirectory.canonicalFile,
            modelIdleTimeoutMs)
        return Decoder(File.createTempFile("speech-asr-", ".wav", app.cacheDir), configuration,
            app.getSystemService(PowerManager::class.java))
    }

    data class WindowText(val text: String, val timing: HtpWindowTiming, val modelLoadMs: Double = 0.0)

    /** Serialize decode and close from the owning caller. Each window is independently recognized. */
    class Decoder internal constructor(
        private val input: File,
        private val configuration: WorkerConfiguration,
        private val power: PowerManager?,
    ) : AutoCloseable {
        private var closed = false
        var modelLoadMs: Double? = null
            private set

        suspend fun decode(samples: FloatArray): WindowText? = withContext(Dispatchers.IO) {
            check(!closed)
            require(samples.size in 1..WINDOW_SAMPLES.toInt())
            currentCoroutineContext().ensureActive()
            if (samples.all { it == 0f }) return@withContext null
            try {
                PcmWav.writeMono(input, samples, SAMPLE_RATE)
                InferenceServer.transcribe(configuration, input, power).also { window ->
                    modelLoadMs = (modelLoadMs ?: 0.0) + window.modelLoadMs
                }
            } catch (failure: Exception) {
                close()
                throw failure
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            input.delete()
        }
    }

    private fun requireAvailable() {
        val status = availability()
        if (status != HtpAvailability.READY) throw HtpUnavailableException(status)
    }

    private fun complete(directory: File, entries: JSONObject): Boolean = entries.keys().asSequence().all {
        val file = File(directory, it)
        file.isFile && file.canRead() && file.length() == entries.getLong(it)
    }

    internal data class WorkerConfiguration(val executable: File, val runtime: File, val model: File, val idleMs: Int)

    private object InferenceServer {
        private val execution = Mutex()
        @Volatile private var worker: WorkerPipe? = null
        private var configuration: WorkerConfiguration? = null
        private var sequence = 0L
        private val usage = MutableStateFlow(HtpServiceStatus())

        fun status(): HtpServiceStatus {
            val snapshot = usage.value
            val live = worker?.takeIf { it.isAlive }
            return snapshot.copy(state = live?.state ?: HtpServiceState.STOPPED, memoryRssKiB = live?.rssKiB)
        }

        suspend fun transcribe(requested: WorkerConfiguration, input: File, power: PowerManager?): WindowText = execution.withLock {
            currentCoroutineContext().ensureActive()
            try {
                val live = worker?.takeIf { it.isAlive && configuration == requested } ?: run {
                    stop()
                    start(requested).also { worker = it; configuration = requested }
                }
                sequence++
                // Sample after acquiring the worker, so queued windows get a current vote.
                val thermal = try {
                    if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null
                } catch (_: RuntimeException) {
                    null // A missing thermal reading keeps the previous TURBO vote.
                }
                val burst = thermal == PowerManager.THERMAL_STATUS_NONE || thermal == PowerManager.THERMAL_STATUS_LIGHT
                val mode = if (burst) "burst\t" else ""
                live.send("$sequence\t$mode${input.absolutePath}")
                val fields = live.frame().split('\t', limit = 12)
                check(fields.size == 12 && fields[0].toLongOrNull() == sequence) { "Invalid HTP response." }
                val timing = HtpWindowTiming(
                    melMs = fields[1].toDouble(), encoderMs = fields[2].toDouble(), prefillMs = fields[3].toDouble(),
                    decodeMs = fields[4].toDouble(), tokens = fields[5].toInt(), decodeGraphMs = fields[7].toDouble(),
                    decodeKvMs = fields[8].toDouble(), decodeCalls = fields[9].toInt(), workerPeakRssKiB = fields[10].toLong(),
                )
                usage.update { previous -> previous.copy(completedWindows = previous.completedWindows + 1,
                    peakRssKiB = maxOf(previous.peakRssKiB ?: 0, timing.workerPeakRssKiB), lastDecodeMs = timing.decodeMs) }
                WindowText(fields[11], timing, fields[6].toDouble())
            } catch (failure: Exception) {
                // After an interrupted exchange, the next client needs a fresh pipe.
                stop()
                throw failure
            }
        }

        private fun stop() {
            worker?.close()
            worker = null
            configuration = null
        }

        private fun start(config: WorkerConfiguration): WorkerPipe {
            val runtime = config.runtime.path
            return WorkerPipe(ProcessBuilder(config.executable.path, runtime, config.model.path, config.idleMs.toString()).apply {
                environment()["LD_LIBRARY_PATH"] = "$runtime:/system/lib64:/system_ext/lib64:/vendor/lib64"
                environment()["ADSP_LIBRARY_PATH"] = runtime
                environment()["QWEN_HTP_SHARED_IO"] = "1"
                environment()["QWEN_HTP_PERFORMANCE"] = "1"
                redirectErrorStream(true)
            }.start())
        }
    }

    private class WorkerPipe(private val child: Process) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val frames = Channel<String>(32)
        private val input = child.outputStream.bufferedWriter()
        val isAlive get() = child.isAlive
        @Volatile var state = HtpServiceState.LOADING
            private set
        @Volatile var rssKiB: Long? = null
            private set

        init {
            scope.launch {
                try {
                    child.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!line.startsWith(PREFIX)) continue
                            val frame = line.removePrefix(PREFIX)
                            if (frame.startsWith("state\t")) {
                                val fields = frame.split('\t')
                                rssKiB = fields[2].toLongOrNull()?.takeIf { it > 0 }
                                state = HtpServiceState.valueOf(fields[1])
                            } else frames.send(frame)
                        }
                    }
                    frames.close()
                } catch (error: Exception) {
                    frames.close(error)
                }
            }
        }

        fun send(line: String) {
            require(line.none { it == '\n' || it == '\r' })
            input.write(line)
            input.newLine()
            input.flush()
        }

        suspend fun frame(): String = try {
            withTimeout(90_000) {
                val value = frames.receiveCatching().getOrNull()
                check(value != null && value != "error") { "The Qualcomm HTP worker failed." }
                value
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("The Qualcomm HTP worker timed out.", timeout)
        }

        override fun close() {
            scope.cancel()
            runCatching { send("quit"); child.waitFor(200, TimeUnit.MILLISECONDS) }
            if (child.isAlive) runCatching { child.destroyForcibly(); child.waitFor(300, TimeUnit.MILLISECONDS) }
            runCatching { child.inputStream.close() }
            runCatching { input.close() }
            frames.cancel()
        }
    }

    companion object {
        // A positive hardware query is immutable for this runtime in the lifetime of the app.
        private val architectures = mutableMapOf<String, Int>()
        @Synchronized private fun detectedArch(runtime: File, worker: File): Int? {
            architectures[runtime.path]?.let { return it }
            return runCatching {
                val process = ProcessBuilder(worker.path, "--device-arch", runtime.path).apply {
                    environment()["LD_LIBRARY_PATH"] = "${runtime.path}:/system/lib64:/system_ext/lib64:/vendor/lib64"
                    environment()["ADSP_LIBRARY_PATH"] = runtime.path
                    redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                }.start()
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) null
                    else process.inputStream.bufferedReader().useLines { lines ->
                        lines.firstOrNull { it.startsWith("QWEN_HTP_ARCH ") }?.substringAfter(' ')?.toIntOrNull()
                    }?.also { architectures[runtime.path] = it }
                } finally {
                    process.destroyForcibly()
                }
            }.getOrNull()
        }

        fun serviceStatus(): HtpServiceStatus = InferenceServer.status()

        private const val WINDOW_SAMPLES = 24L * SAMPLE_RATE
        private const val PREFIX = "SOUND_QWEN_HTP\t"
    }
}
