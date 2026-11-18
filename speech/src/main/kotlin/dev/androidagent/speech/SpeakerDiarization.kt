package dev.androidagent.speech

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class SpeakerDiarizationResult(val turns: List<SpeakerTurn>, val warning: String? = null)

/** Whole-recording diarization with recording-local speaker IDs. */
class SpeakerDiarization(private val segmentation: File, private val embedding: File, private val threads: Int) {
    val available get() = segmentation.isFile && embedding.isFile

    suspend fun analyze(file: File, count: Int? = null): SpeakerDiarizationResult = withContext(Dispatchers.IO) {
        require(available && threads in 1..8 && (count == null || count in 1..32))
        val wav = file.openSpeechAudio()
        require(wav.durationMs <= MAX_DURATION_MS)
        val samples = FloatArray(wav.sampleCount.toInt())
        var offset = 0
        while (offset < samples.size) {
            ensureActive()
            val size = minOf(PcmWav.MAX_WINDOW_SAMPLES, samples.size - offset)
            wav.readMono(offset.toLong(), size).copyInto(samples, offset)
            offset += size
        }
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(segmentation.absolutePath), numThreads = threads),
            embedding = SpeakerEmbeddingExtractorConfig(model = embedding.absolutePath, numThreads = threads),
            clustering = FastClusteringConfig(numClusters = count ?: -1, threshold = 0.5f),
            minDurationOn = 0.3f, minDurationOff = 0.5f,
        )
        val model = OfflineSpeakerDiarization(config = config)
        val turns = try {
            model.process(samples).mapNotNull {
                val start = (it.start * 1000).toLong().coerceAtLeast(0)
                val end = (it.end * 1000).toLong().coerceAtMost(wav.durationMs)
                if (end > start) SpeakerTurn(start, end, it.speaker) else null
            }
        } finally {
            model.release()
        }
        ensureActive()
        if (count != null || turns.isEmpty()) return@withContext SpeakerDiarizationResult(turns)

        val observations = SpeakerEmbeddings(embedding, threads).openExtractor().use { extractor ->
            turns.map { turn ->
                ensureActive()
                val ranges = cleanSpeech(turn, turns)
                val cleanMs = ranges.sumOf { it.last + 1 - it.first }
                val size = (minOf(cleanMs, 6_000L) * 16).toInt()
                val vector = if (size >= 4_800) {
                    val clip = FloatArray(size)
                    var written = 0
                    for (range in ranges) {
                        val start = (range.first * 16).toInt()
                        val take = minOf((range.last + 1 - range.first).toInt() * 16, size - written)
                        samples.copyInto(clip, written, start, start + take)
                        written += take
                        if (written == size) break
                    }
                    extractor.embed(clip)
                } else null
                SpeakerObservation(turn, vector, cleanMs)
            }
        }
        val clustered = SpeakerClustering().cluster(observations)
        val warning = when {
            clustered.anchorCount == 0 -> "INSUFFICIENT_SPEECH"
            clustered.unknownTurns > 0 -> "UNKNOWN_SPEAKERS"
            else -> null
        }
        SpeakerDiarizationResult(clustered.turns, warning)
    }

    private fun cleanSpeech(turn: SpeakerTurn, all: List<SpeakerTurn>): List<LongRange> {
        var ranges = listOf(turn.startMs until turn.endMs)
        for (other in all) {
            if (other.speaker == turn.speaker || other.endMs <= turn.startMs || other.startMs >= turn.endMs) continue
            ranges = ranges.flatMap { range ->
                val end = range.last + 1
                if (other.endMs <= range.first || other.startMs >= end) listOf(range)
                else buildList {
                    if (other.startMs > range.first) add(range.first until other.startMs)
                    if (other.endMs < end) add(other.endMs until end)
                }
            }
        }
        return ranges
    }

    companion object {
        const val MAX_DURATION_MS = 80L * 60 * 1000
    }
}
