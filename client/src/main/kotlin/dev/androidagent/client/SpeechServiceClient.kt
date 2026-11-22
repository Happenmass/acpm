package dev.androidagent.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class RemoteAsrStatus { READY, UNSUPPORTED_DEVICE, RUNTIME_NOT_INSTALLED, MODELS_NOT_INSTALLED }
enum class McpTurnOutcome { Succeeded, Failed, Cancelled, Interrupted }

data class SpeechServiceStatus(val asr: RemoteAsrStatus, val speakersReady: Boolean, val servicePid: Int)

data class RemoteSpeechWindow(val text: String, val modelLoadMs: Double?, val decodeMs: Double, val servicePid: Int)
data class RemoteSpeakerTurn(val startMs: Long, val endMs: Long, val speaker: Int)
data class RemoteSpeakers(val turns: List<RemoteSpeakerTurn>, val warning: String?)

class SpeechServiceException internal constructor(
    val reason: String,
    val requestId: String?,
    val stage: String?,
    val detail: String?,
    val nativeCode: String?,
    val elapsedMs: Long?,
) : IllegalStateException("AI SDK speech service: $reason") {
    constructor(reason: String) : this(reason, null, null, null, null, null)
}

/** Application-owned Binder client for speech inference and optional MCP diagnostics. */
class SpeechServiceClient(context: Context, val servicePackage: String) {
    private val app = context.applicationContext
    private val bindingLock = Any()
    private var bound = false
    private val server = MutableStateFlow<Messenger?>(null)
    private val _status = MutableStateFlow<SpeechServiceStatus?>(null)
    val status = _status.asStateFlow()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Bundle>>()
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        val data = message.data
        data.getString(SpeechWire.ID)?.let { pending.remove(it)?.complete(data) }
        true
    })

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { server.value = Messenger(binder) }
        override fun onServiceDisconnected(name: ComponentName) { disconnect() }
        override fun onBindingDied(name: ComponentName) { releaseBinding() }
        override fun onNullBinding(name: ComponentName) { releaseBinding() }
    }

    private fun disconnect() {
        server.value = null
        _status.value = null
        pending.values.forEach { it.completeExceptionally(SpeechServiceException("DISCONNECTED")) }
    }

    private fun releaseBinding() = synchronized(bindingLock) {
        disconnect()
        if (bound) {
            app.unbindService(connection)
            bound = false
        }
    }

    suspend fun refresh(): SpeechServiceStatus {
        val reply = try {
            request(SpeechWire.STATUS, Bundle(), 5_000)
        } catch (failure: SpeechServiceException) {
            _status.value = null
            throw failure
        }
        val state = SpeechServiceStatus(RemoteAsrStatus.valueOf(requireNotNull(reply.getString(SpeechWire.ASR_STATE))),
            reply.getBoolean(SpeechWire.SPEAKER_READY), reply.getInt(SpeechWire.SERVICE_PID))
        _status.value = state
        return state
    }

    /** Requires one 16 kHz mono PCM16 window of up to 24 seconds. */
    suspend fun transcribeWindow(wav: File): RemoteSpeechWindow {
        val reply = requestAudio(SpeechWire.ASR, wav, Bundle(), 120_000)
        return RemoteSpeechWindow(reply.getString(SpeechWire.TEXT).orEmpty(),
            reply.getDouble(SpeechWire.MODEL_LOAD_MS).takeIf { reply.containsKey(SpeechWire.MODEL_LOAD_MS) },
            reply.getDouble(SpeechWire.DECODE_MS), reply.getInt(SpeechWire.SERVICE_PID))
    }

    suspend fun diarize(wav: File, speakerCount: Int? = null): RemoteSpeakers {
        require(speakerCount == null || speakerCount in 1..32)
        val reply = requestAudio(SpeechWire.SPEAKERS, wav, Bundle().apply {
            putInt(SpeechWire.SPEAKER_COUNT, speakerCount ?: 0)
        }, 900_000)
        val turns = requireNotNull(reply.getLongArray(SpeechWire.TURNS)).asList().chunked(3).map {
            RemoteSpeakerTurn(it[0], it[1], it[2].toInt())
        }
        return RemoteSpeakers(turns, reply.getString(SpeechWire.WARNING))
    }

    /** Report a turn accepted by the Agent runtime without delaying that turn or sharing its endpoint credentials. */
    fun observeMcp(
        scope: CoroutineScope,
        backend: String,
        port: Int,
        toolNames: Set<String>,
        awaitResult: suspend () -> McpTurnOutcome,
    ) = scope.launch {
        val mountId = UUID.randomUUID().toString()
        val tools = toolNames.asSequence().filter(SpeechWire.mcpToolName::matches).sorted()
            .take(SpeechWire.MAX_REPORTED_TOOLS).toCollection(ArrayList())
        suspend fun report(outcome: McpTurnOutcome?) {
            try {
                request(SpeechWire.MCP_REPORT, Bundle().apply {
                    putString(SpeechWire.MCP_ID, mountId)
                    if (outcome == null) {
                        putString(SpeechWire.MCP_BACKEND, backend)
                        putInt(SpeechWire.MCP_PORT, port)
                        putStringArrayList(SpeechWire.MCP_TOOLS, tools)
                    } else {
                        putString(SpeechWire.MCP_OUTCOME, outcome.name)
                    }
                }, 2_000)
            } catch (_: SpeechServiceException) {
                // Agent execution does not require a diagnostics console or a compatible service version.
            }
        }

        var result = McpTurnOutcome.Interrupted
        try {
            report(null)
            result = awaitResult()
        } finally {
            withContext(NonCancellable) { report(result) }
        }
    }

    private suspend fun requestAudio(op: Int, wav: File, data: Bundle, timeoutMs: Long): Bundle =
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(wav, ParcelFileDescriptor.MODE_READ_ONLY).use {
                data.putParcelable(SpeechWire.AUDIO, it)
                request(op, data, timeoutMs)
            }
        }

    private suspend fun request(op: Int, data: Bundle, timeoutMs: Long): Bundle {
        synchronized(bindingLock) {
            if (!bound) {
                when (app.packageManager.checkSignatures(app.packageName, servicePackage)) {
                    PackageManager.SIGNATURE_MATCH -> Unit
                    PackageManager.SIGNATURE_UNKNOWN_PACKAGE -> throw SpeechServiceException("SERVICE_NOT_INSTALLED")
                    else -> throw SpeechServiceException("ACCESS_DENIED")
                }
                bound = try {
                    app.bindService(Intent(SpeechWire.ACTION).setPackage(servicePackage), connection, Context.BIND_AUTO_CREATE)
                } catch (_: SecurityException) {
                    throw SpeechServiceException("ACCESS_DENIED")
                }
                if (!bound) throw SpeechServiceException("SERVICE_NOT_INSTALLED")
            }
        }
        val target = withTimeoutOrNull(5_000) { server.filterNotNull().first() }
            ?: throw SpeechServiceException("DISCONNECTED")
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<Bundle>()
        pending[id] = result
        data.putString(SpeechWire.ID, id)
        var received = false
        try {
            target.send(message(op, data))
            val reply = withTimeoutOrNull(timeoutMs) { result.await() } ?: throw SpeechServiceException("TIMEOUT")
            received = true
            reply.getString(SpeechWire.ERROR)?.let { reason ->
                throw SpeechServiceException(reason, id, reply.getString(SpeechWire.FAILURE_STAGE),
                    reply.getString(SpeechWire.FAILURE_DETAIL), reply.getString(SpeechWire.NATIVE_ERROR_CODE),
                    reply.getLong(SpeechWire.FAILURE_ELAPSED_MS).takeIf { reply.containsKey(SpeechWire.FAILURE_ELAPSED_MS) })
            }
            return reply
        } catch (_: RemoteException) {
            throw SpeechServiceException("DISCONNECTED")
        } finally {
            pending.remove(id)
            if (!received) runCatching {
                target.send(message(SpeechWire.CANCEL, Bundle().apply { putString(SpeechWire.ID, id) }))
            }
        }
    }

    private fun message(op: Int, body: Bundle) = Message.obtain(null, op).apply {
        data = body
        replyTo = replies
    }
}
