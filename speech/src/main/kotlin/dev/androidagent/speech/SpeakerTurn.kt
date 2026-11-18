package dev.androidagent.speech

/** Recording-local speaker attribution. A negative speaker value means unknown. */
data class SpeakerTurn(val startMs: Long, val endMs: Long, val speaker: Int) {
    init { require(startMs >= 0 && endMs > startMs) }
}
