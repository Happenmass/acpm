package dev.androidagent.speech

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Half-open range in the source file's 16 kHz sample timeline. */
data class SpeechRange(val startSample: Long, val endSample: Long) {
    init { require(startSample >= 0 && endSample > startSample) }
    val startMs: Long get() = startSample * 1000 / SAMPLE_RATE
    val endMs: Long get() = (endSample * 1000 + SAMPLE_RATE - 1) / SAMPLE_RATE
}

internal const val SAMPLE_RATE = 16000

internal fun File.openSpeechAudio(): PcmWav = PcmWav.open(this).also {
    require(it.sampleRate == SAMPLE_RATE && it.channels == 1) { "Expected mono 16 kHz PCM16 WAV." }
}

internal fun PcmWav.checkRange(range: SpeechRange) {
    require(range.endSample <= sampleCount) { "Speech range exceeds the input audio." }
}

/** Pyannote segmentation 3.0 speech detection on the original audio timeline. */
class PyannoteVad(private val model: File) {
    suspend fun detect(
        audio: File,
        minimumSpeechMs: Int = 300,
        mergeGapMs: Int = 500,
        onProgress: (Float) -> Unit = {},
    ): List<SpeechRange> = withContext(Dispatchers.IO) {
        require(minimumSpeechMs >= 0 && mergeGapMs >= 0)
        val wav = audio.openSpeechAudio()
        require(model.isFile) { "Pyannote model is missing." }
        Jni.ensureLoaded()
        val frameCount = ((wav.sampleCount + FRAME_HOP - 1) / FRAME_HOP).toInt()
        val votes = FloatArray(frameCount)
        val weights = FloatArray(frameCount)
        val handle = open(model.absolutePath)
        try {
            var start = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = minOf(WINDOW.toLong(), wav.sampleCount - start).toInt()
                val samples = wav.readMono(start, count).copyOf(WINDOW)
                val labels = classify(handle, samples)
                val offset = ((start + FRAME_HOP / 2) / FRAME_HOP).toInt()
                labels.forEachIndexed { frame, label ->
                    val bin = offset + frame
                    if (bin < frameCount) {
                        val weight = 0.54f - 0.46f * kotlin.math.cos(2.0 * Math.PI * frame / (labels.size - 1)).toFloat()
                        votes[bin] += label * weight
                        weights[bin] += weight
                    }
                }
                onProgress((start + count).toFloat() / wav.sampleCount)
                if (start + count == wav.sampleCount) break
                start += WINDOW_HOP
            }
        } finally {
            close(handle)
        }
        val raw = mutableListOf<SpeechRange>()
        var start = -1L
        for (frame in 0..frameCount) {
            val active = frame < frameCount && weights[frame] > 0 && votes[frame] >= weights[frame] / 2
            val sample = minOf(wav.sampleCount, frame.toLong() * FRAME_HOP + FRAME_CENTER)
            if (active && start < 0) start = if (frame == 0) 0 else sample
            if (!active && start >= 0) {
                if (sample > start) raw.add(SpeechRange(start, sample))
                start = -1
            }
        }
        val merged = mutableListOf<SpeechRange>()
        val maxGap = mergeGapMs.toLong() * 16
        for (range in raw) {
            val previous = merged.lastOrNull()
            if (previous != null && range.startSample - previous.endSample <= maxGap) {
                merged[merged.lastIndex] = SpeechRange(previous.startSample, range.endSample)
            } else merged.add(range)
        }
        currentCoroutineContext().ensureActive()
        merged.filter { it.endSample - it.startSample >= minimumSpeechMs.toLong() * 16 }
    }

    private external fun open(path: String): Long
    private external fun classify(handle: Long, samples: FloatArray): ByteArray
    private external fun close(handle: Long)

    private object Jni {
        init { System.loadLibrary("android_ai_vad") }
        fun ensureLoaded() = Unit
    }

    private companion object {
        const val WINDOW = 160000
        const val WINDOW_HOP = 16000
        const val FRAME_HOP = 270
        const val FRAME_CENTER = 495
    }
}

/** ERes2NetV2 embeddings for explicitly supplied speech ranges. */
class SpeakerEmbeddings(private val model: File, private val threads: Int = 2) {
    suspend fun extract(
        audio: File,
        ranges: List<SpeechRange>,
        onProgress: (Float) -> Unit = {},
    ): List<SpeakerObservation> = withContext(Dispatchers.IO) {
        val wav = audio.openSpeechAudio()
        require(threads in 1..8 && model.isFile) { "Invalid speaker model or thread count." }
        ranges.forEach(wav::checkRange)
        if (ranges.isEmpty()) return@withContext emptyList()
        val extractor = openExtractor()
        try {
            ranges.mapIndexed { index, range ->
                currentCoroutineContext().ensureActive()
                val count = minOf(range.endSample - range.startSample, 6L * SAMPLE_RATE).toInt()
                val vector = extractor.embed(wav.readMono(range.startSample, count))
                currentCoroutineContext().ensureActive()
                onProgress((index + 1).toFloat() / ranges.size)
                SpeakerObservation(SpeakerTurn(range.startMs, range.endMs, SpeakerClustering.UNKNOWN),
                    vector, count.toLong() * 1000 / SAMPLE_RATE)
            }
        } finally {
            extractor.close()
        }
    }

    /** Reuse the model across caller-provided clean audio windows. */
    fun openExtractor(): Extractor {
        require(threads in 1..8 && model.isFile) { "Invalid speaker model or thread count." }
        return Extractor(SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(model = model.absolutePath, numThreads = threads),
        ))
    }

    class Extractor internal constructor(private val engine: SpeakerEmbeddingExtractor) : AutoCloseable {
        private var closed = false

        fun embed(samples: FloatArray): FloatArray? {
            check(!closed)
            require(samples.isNotEmpty() && samples.size <= 6 * SAMPLE_RATE)
            val stream = engine.createStream()
            return try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                stream.inputFinished()
                if (engine.isReady(stream)) engine.compute(stream) else null
            } finally {
                stream.release()
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            engine.release()
        }
    }
}
