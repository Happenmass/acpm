package dev.androidagent.sample

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.androidagent.client.SpeechWire
import dev.androidagent.speech.HtpInferenceException
import dev.androidagent.speech.HtpUnavailableException
import dev.androidagent.speech.SpeakerDiarization
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SpeechServiceActivity(val active: Int = 0, val completed: Int = 0, val failed: Int = 0,
    val callers: Map<String, Int> = emptyMap())

class AiSpeechService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val models by lazy { SpeechModels(this) }
    private val failures by lazy { SpeechFailures(this) }
    private val requests = mutableMapOf<Pair<IBinder, String>, Job>()
    private val speakerLock = Mutex()
    private val receiver = Messenger(Handler(Looper.getMainLooper(), ::receive))

    override fun onBind(intent: Intent): IBinder = receiver.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun receive(message: Message): Boolean {
        val reply = message.replyTo ?: return false
        val uid = message.sendingUid
        val data = message.data
        val id = data.getString(SpeechWire.ID)?.takeIf { it.length == 36 } ?: return false
        val key = reply.binder to id
        val op = message.what
        if (packageManager.checkSignatures(uid, Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            SpeechWire.audio(data)?.close()
            reply(reply, id, Bundle().apply { putString(SpeechWire.ERROR, "ACCESS_DENIED") })
            return true
        }
        if (op == SpeechWire.CANCEL) {
            requests[key]?.cancel()
            return true
        }
        val fd = SpeechWire.audio(data)
        val caller = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid $uid"
        if (op == SpeechWire.MCP_REPORT) {
            fd?.close()
            val recorded = McpRegistrations.report(uid, reply.binder, caller, data)
            reply(reply, id, Bundle().apply { if (!recorded) putString(SpeechWire.ERROR, "INVALID_MCP_REPORT") })
            return true
        }
        val operation = when (op) {
            SpeechWire.STATUS -> "STATUS"
            SpeechWire.ASR -> "ASR"
            SpeechWire.SPEAKERS -> "SPEAKERS"
            else -> null
        }
        val startedAt = SystemClock.elapsedRealtime()
        suspend fun failureReply(reason: String, stage: String, error: Throwable? = null): Bundle {
            if (operation == null) return Bundle().apply { putString(SpeechWire.ERROR, reason) }
            val audio = error as? AudioFailure
            val htp = error as? HtpInferenceException
            val errno = generateSequence(error) { it.cause }.take(8).filterIsInstance<ErrnoException>().firstOrNull()
            val entry = SpeechFailure(System.currentTimeMillis(), SystemClock.elapsedRealtime() - startedAt,
                id.takeIf(requestIdPattern::matches), caller, operation, audio?.stage ?: htp?.stage ?: stage, reason,
                audio?.detail ?: htp?.detail ?: error?.javaClass?.simpleName ?: reason,
                htp?.nativeCode ?: errno?.let { "errno ${it.errno}" }, Process.myPid())
            withContext(Dispatchers.IO) { failures.record(entry) }
            return entry.reply()
        }
        if (key in requests || requests.size >= 8) {
            fd?.close()
            scope.launch { reply(reply, id, failureReply("BUSY", "BINDER_ADMISSION")) }
            return true
        }
        val inference = op == SpeechWire.ASR || op == SpeechWire.SPEAKERS
        if (inference) activity.update { it.copy(active = it.active + 1) }
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val death = IBinder.DeathRecipient { requestsOnMain { requests[key]?.cancel() } }
            var stage = "BINDER_REQUEST"
            var asrPhase: String? = null
            suspend fun failed(reason: String, error: Throwable) {
                if (inference) activity.update { it.copy(failed = it.failed + 1) }
                reply(reply, id, failureReply(reason, stage, error).apply {
                    asrPhase?.let { putString(SpeechWire.ASR_PHASE, it) }
                })
            }
            try {
                reply.binder.linkToDeath(death, 0)
                val result = withContext(Dispatchers.IO) {
                    when (op) {
                        SpeechWire.STATUS -> {
                            stage = "SERVICE_STATUS"
                            models.prepareRuntime()
                            Bundle().apply {
                                putString(SpeechWire.ASR_STATE, models.asrAvailability().name)
                                putBoolean(SpeechWire.SPEAKER_READY, models.speakerModelsAvailable)
                            }
                        }
                        SpeechWire.ASR -> {
                            stage = "MODEL_PREPARE"
                            asrPhase = "prepare"
                            models.prepareAsr()
                            asrPhase = "transfer"
                            withAudio(fd, 1_000_000) { wav ->
                                stage = "ASR"
                                asrPhase = "inference"
                                val result = models.asr().transcribe(wav)
                                stage = "RESULT_VALIDATION"
                                require(result.executedWindows <= 1)
                                Bundle().apply {
                                    putString(SpeechWire.TEXT, result.text)
                                    result.modelLoadMs?.let { putDouble(SpeechWire.MODEL_LOAD_MS, it) }
                                    putDouble(SpeechWire.DECODE_MS, result.segments.sumOf { it.timing.decodeMs })
                                }
                            }
                        }
                        SpeechWire.SPEAKERS -> speakerLock.withLock {
                            stage = "MODEL_PREPARE"
                            models.prepareSpeakers()
                            val count = data.getInt(SpeechWire.SPEAKER_COUNT).takeIf { it != 0 }
                            withAudio(fd, SpeakerDiarization.MAX_DURATION_MS * 32 + 65536) { wav ->
                                stage = "SPEAKER_ANALYSIS"
                                val result = models.speakers.analyze(wav, count)
                                stage = "RESULT_VALIDATION"
                                require(result.turns.size <= 12_000)
                                Bundle().apply {
                                    putLongArray(SpeechWire.TURNS, LongArray(result.turns.size * 3) { index ->
                                        val turn = result.turns[index / 3]
                                        when (index % 3) { 0 -> turn.startMs; 1 -> turn.endMs; else -> turn.speaker.toLong() }
                                    })
                                    putString(SpeechWire.WARNING, result.warning)
                                }
                            }
                        }
                        else -> error("Unsupported operation")
                    }
                }
                if (inference) activity.update { it.copy(completed = it.completed + 1,
                    callers = it.callers + (caller to ((it.callers[caller] ?: 0) + 1))) }
                reply(reply, id, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val reason = (failure as? HtpUnavailableException)?.reason?.name ?: "INFERENCE_FAILED"
                failed(reason, failure)
            } catch (failure: LinkageError) {
                failed("RUNTIME_NOT_INSTALLED", failure)
            } finally {
                requests.remove(key)
                runCatching { reply.binder.unlinkToDeath(death, 0) }
                runCatching { fd?.close() }
                if (inference) activity.update { it.copy(active = it.active - 1) }
            }
        }
        requests[key] = job
        job.start()
        return true
    }

    private fun requestsOnMain(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }

    private suspend fun <T> withAudio(descriptor: ParcelFileDescriptor?, maximumBytes: Long, action: suspend (File) -> T): T {
        if (descriptor == null) throw AudioFailure("AUDIO_DESCRIPTOR", "MISSING_DESCRIPTOR")
        val stat = try {
            Os.fstat(descriptor.fileDescriptor)
        } catch (failure: ErrnoException) {
            throw AudioFailure("AUDIO_DESCRIPTOR", "DESCRIPTOR_STAT_FAILED", failure)
        }
        val size = if (OsConstants.S_ISREG(stat.st_mode)) stat.st_size else -1
        if (size !in 44..maximumBytes) {
            throw AudioFailure("AUDIO_DESCRIPTOR", if (size < 0) "UNKNOWN_DESCRIPTOR_SIZE" else "INVALID_DESCRIPTOR_SIZE")
        }
        val wav = try {
            File.createTempFile("speech-request-", ".wav", cacheDir)
        } catch (failure: IOException) {
            throw AudioFailure("AUDIO_STAGING", "TEMP_FILE_FAILED", failure)
        }
        try {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    wav.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytes = 0L
                        while (true) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val read = try {
                                input.read(buffer)
                            } catch (failure: IOException) {
                                throw AudioFailure("AUDIO_READ", "DESCRIPTOR_READ_FAILED", failure)
                            }
                            if (read == -1) break
                            bytes += read
                            if (bytes > maximumBytes) throw AudioFailure("AUDIO_READ", "AUDIO_LIMIT_EXCEEDED")
                            output.write(buffer, 0, read)
                        }
                        if (bytes != size) throw AudioFailure("AUDIO_READ", "DESCRIPTOR_SIZE_CHANGED")
                    }
                }
            } catch (failure: IOException) {
                throw AudioFailure("AUDIO_STAGING", "STAGING_IO_FAILED", failure)
            }
            return action(wav)
        } finally {
            wav.delete()
        }
    }

    private class AudioFailure(val stage: String, val detail: String, cause: Exception? = null) :
        IllegalStateException(detail, cause)

    private fun reply(target: Messenger, id: String, result: Bundle) {
        runCatching {
            target.send(Message.obtain().apply {
                result.putString(SpeechWire.ID, id)
                result.putInt(SpeechWire.SERVICE_PID, Process.myPid())
                data = result
            })
        }
    }

    internal companion object {
        val activity = MutableStateFlow(SpeechServiceActivity())
        private val requestIdPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
