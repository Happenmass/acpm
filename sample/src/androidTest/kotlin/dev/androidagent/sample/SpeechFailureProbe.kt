package dev.androidagent.sample

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import dev.androidagent.client.RemoteAsrStatus
import dev.androidagent.client.SpeechServiceClient
import dev.androidagent.client.SpeechServiceException
import dev.androidagent.client.SpeechWire
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

internal class SpeechFailureProbe(private val context: Context) {
    private val history = SpeechFailures(context)
    private val client = SpeechServiceClient(context, context.packageName)
    private val expected = mapOf(
        "AUDIO_DESCRIPTOR" to "INVALID_DESCRIPTOR_SIZE",
        "AUDIO_READ" to "DESCRIPTOR_READ_FAILED",
        "WAV_VALIDATION" to "INVALID_WAV",
        "HTP_MODEL_LOAD" to "QNN context metadata",
    )

    suspend fun run(): String {
        val fixture = File(context.filesDir, "speech/fixture.wav")
        val status = client.refresh()
        check(status.asr == RemoteAsrStatus.READY)
        val oversized = File.createTempFile("speech-probe-private-path-", ".wav", context.cacheDir)
        try {
            RandomAccessFile(oversized, "rw").use { it.setLength(1_000_001) }
            verifyClientFailure(oversized, "AUDIO_DESCRIPTOR")
            oversized.writeBytes(ByteArray(64))
            verifyClientFailure(oversized, "WAV_VALIDATION")
        } finally {
            oversized.delete()
        }

        val bytes = fixture.readBytes()
        val failedProxy = proxyRequest(bytes, failRead = true)
        check(failedProxy.getString(SpeechWire.ERROR) == "INFERENCE_FAILED")
        check(failedProxy.getString(SpeechWire.FAILURE_STAGE) == "AUDIO_READ") { "Proxy response: $failedProxy" }
        check(failedProxy.getString(SpeechWire.FAILURE_DETAIL) == expected.getValue("AUDIO_READ"))
        check(failedProxy.getString(SpeechWire.NATIVE_ERROR_CODE) == "errno ${OsConstants.EIO}")

        val modelDirectory = File(context.noBackupFilesDir, "speech-models").listFiles().orEmpty()
            .single { it.isDirectory && it.name.startsWith("qwen-") }
        val cnn = File(modelDirectory, "audio_cnn.bin")
        val saved = File(modelDirectory, "audio_cnn.probe-backup")
        check(!saved.exists() && cnn.isFile && cnn.renameTo(saved))
        try {
            RandomAccessFile(cnn, "rw").use { it.setLength(saved.length()) }
            val native = verifyClientFailure(fixture, "HTP_MODEL_LOAD")
            check(native.nativeCode?.matches(Regex("0x[0-9a-f]+")) == true)
        } finally {
            check((!cnn.exists() || cnn.delete()) && saved.renameTo(cnn))
        }

        val after = client.transcribeWindow(fixture)
        check(after.text.contains("speech recognition", ignoreCase = true) && after.modelLoadMs != null)
        val goodProxy = proxyRequest(bytes, failRead = false)
        check(goodProxy.getString(SpeechWire.ERROR) == null && goodProxy.getString(SpeechWire.TEXT) == after.text)
        verifyHistory()
        return "Speech diagnostics passed. Descriptor size, FUSE read, WAV validation, QNN model load, and recovery through file and proxy descriptors."
    }

    private suspend fun verifyClientFailure(file: File, stage: String): SpeechServiceException {
        val failure = try {
            client.transcribeWindow(file)
            error("Expected $stage failure")
        } catch (failure: SpeechServiceException) {
            failure
        }
        check(failure.reason == "INFERENCE_FAILED" && failure.stage == stage &&
            failure.detail == expected.getValue(stage)) { "Unexpected response: ${failure.stage} / ${failure.detail}" }
        val recorded = history.recent().single { it.requestId == failure.requestId }
        check(recorded.stage == stage && recorded.elapsedMs == failure.elapsedMs)
        check(recorded.operation == "ASR" && recorded.caller == context.packageName)
        return failure
    }

    fun verifyRestart() {
        check(AiSpeechService.activity.value.failed == 0)
        verifyHistory()
    }

    private fun verifyHistory() {
        val entries = history.recent()
        for ((stage, detail) in expected) {
            val entry = entries.first { it.stage == stage }
            check(entry.detail == detail && entry.elapsedMs >= 0 && entry.atMs > 0)
            check(runCatching { UUID.fromString(entry.requestId) }.isSuccess)
        }
        check(entries.none { it.json().toString().contains("speech-probe-private-path") })
    }

    private suspend fun proxyRequest(wav: ByteArray, failRead: Boolean): Bundle {
        val connected = CompletableDeferred<Messenger>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(Messenger(service))
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        check(context.bindService(Intent(context, AiSpeechService::class.java), connection, Context.BIND_AUTO_CREATE))
        val thread = HandlerThread("SpeechFailureProbeAudio").apply { start() }
        try {
            val server = withTimeout(5_000) { connected.await() }
            val storage = context.getSystemService(StorageManager::class.java)
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = wav.size.toLong()

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (failRead) throw ErrnoException("speech-probe-private-path", OsConstants.EIO)
                    if (offset !in 0 until wav.size.toLong()) return 0
                    val count = minOf(size, wav.size - offset.toInt())
                    wav.copyInto(data, endIndex = offset.toInt() + count, startIndex = offset.toInt())
                    return count
                }

                override fun onRelease() = Unit
            }
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper)).use { fd ->
                val reply = CompletableDeferred<Bundle>()
                val target = Messenger(Handler(Looper.getMainLooper()) { message -> reply.complete(message.data); true })
                server.send(Message.obtain(null, SpeechWire.ASR).apply {
                    replyTo = target
                    data = Bundle().apply {
                        putString(SpeechWire.ID, UUID.randomUUID().toString())
                        putParcelable(SpeechWire.AUDIO, fd)
                    }
                })
                withTimeout(30_000) { reply.await() }
            }
        } finally {
            thread.quitSafely()
            context.unbindService(connection)
        }
    }
}
