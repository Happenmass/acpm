package dev.androidagent.client

import android.os.Bundle
import android.os.ParcelFileDescriptor

/** Versioned Android transport shared by the SDK client and service APK. */
object SpeechWire {
    const val ACTION = "dev.androidagent.action.SPEECH_SERVICE_V1"
    const val STATUS = 1
    const val ASR = 2
    const val SPEAKERS = 3
    const val CANCEL = 4
    const val MCP_REPORT = 5
    const val ID = "id"
    const val AUDIO = "audio"
    const val ERROR = "error"
    const val FAILURE_STAGE = "failureStage"
    const val FAILURE_DETAIL = "failureDetail"
    const val NATIVE_ERROR_CODE = "nativeErrorCode"
    const val FAILURE_ELAPSED_MS = "failureElapsedMs"
    const val ASR_PHASE = "asrPhase"
    const val ASR_STATE = "asr"
    const val SPEAKER_READY = "speakerReady"
    const val TEXT = "text"
    const val TURNS = "turns"
    const val SPEAKER_COUNT = "speakerCount"
    const val MODEL_LOAD_MS = "modelLoadMs"
    const val DECODE_MS = "decodeMs"
    const val WARNING = "warning"
    const val SERVICE_PID = "servicePid"
    const val MCP_ID = "mountId"
    const val MCP_BACKEND = "backend"
    const val MCP_PORT = "port"
    const val MCP_TOOLS = "tools"
    const val MCP_OUTCOME = "turnOutcome"
    const val MAX_REPORTED_TOOLS = 64

    val mcpToolName = Regex("[A-Za-z0-9_.-]{1,64}")

    @Suppress("DEPRECATION")
    fun audio(data: Bundle): ParcelFileDescriptor? = data.getParcelable(AUDIO)
}
