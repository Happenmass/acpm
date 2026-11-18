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
import dev.androidagent.client.SpeechWire
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
        if (key in requests || requests.size >= 8) {
            fd?.close()
            reply(reply, id, Bundle().apply { putString(SpeechWire.ERROR, "BUSY") })
            return true
        }
        val inference = op == SpeechWire.ASR || op == SpeechWire.SPEAKERS
        if (inference) activity.update { it.copy(active = it.active + 1) }
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val death = IBinder.DeathRecipient { requestsOnMain { requests[key]?.cancel() } }
            try {
                reply.binder.linkToDeath(death, 0)
                val result = withContext(Dispatchers.IO) {
                    when (op) {
                        SpeechWire.STATUS -> {
                            models.prepareRuntime()
                            Bundle().apply {
                                putString(SpeechWire.ASR_STATE, models.asrAvailability().name)
                                putBoolean(SpeechWire.SPEAKER_READY, models.speakerModelsAvailable)
                            }
                        }
                        SpeechWire.ASR -> {
                            models.prepareAsr()
                            withAudio(requireNotNull(fd), 1_000_000) { wav ->
                                val result = models.asr().transcribe(wav)
                                require(result.executedWindows <= 1)
                                Bundle().apply {
                                    putString(SpeechWire.TEXT, result.text)
                                    result.modelLoadMs?.let { putDouble(SpeechWire.MODEL_LOAD_MS, it) }
                                    putDouble(SpeechWire.DECODE_MS, result.segments.sumOf { it.timing.decodeMs })
                                }
                            }
                        }
                        SpeechWire.SPEAKERS -> speakerLock.withLock {
                            models.prepareSpeakers()
                            val count = data.getInt(SpeechWire.SPEAKER_COUNT).takeIf { it != 0 }
                            withAudio(requireNotNull(fd), SpeakerDiarization.MAX_DURATION_MS * 32 + 65536) { wav ->
                                val result = models.speakers.analyze(wav, count)
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
                if (inference) activity.update { it.copy(failed = it.failed + 1) }
                val reason = (failure as? HtpUnavailableException)?.reason?.name ?: "INFERENCE_FAILED"
                reply(reply, id, Bundle().apply { putString(SpeechWire.ERROR, reason) })
            } catch (_: LinkageError) {
                if (inference) activity.update { it.copy(failed = it.failed + 1) }
                reply(reply, id, Bundle().apply { putString(SpeechWire.ERROR, "RUNTIME_NOT_INSTALLED") })
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

    private suspend fun <T> withAudio(descriptor: ParcelFileDescriptor, maximumBytes: Long, action: suspend (File) -> T): T {
        require(descriptor.statSize in 44..maximumBytes)
        val wav = File.createTempFile("speech-request-", ".wav", cacheDir)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                wav.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        bytes += read
                        if (bytes > maximumBytes) throw IOException("Audio limit exceeded")
                        output.write(buffer, 0, read)
                    }
                }
            }
            return action(wav)
        } finally {
            wav.delete()
        }
    }

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
    }
}
