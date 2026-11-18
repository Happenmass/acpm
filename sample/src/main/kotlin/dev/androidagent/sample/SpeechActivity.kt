package dev.androidagent.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.androidagent.speech.PyannoteVad
import dev.androidagent.speech.SpeakerClustering
import dev.androidagent.speech.SpeakerEmbeddings
import dev.androidagent.speech.SpeakerObservation
import dev.androidagent.speech.SpeechRange
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpeechActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val models by lazy { SpeechModels(this) }
    private val directory get() = models.directory
    private var audio: File? = null
    private var embeddings = emptyList<SpeakerObservation>()
    private var job: Job? = null
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The instrumentation fixture is supplied by the caller into private storage.
        if (packageName.endsWith(".fixture")) audio = File(directory, "fixture.wav").takeIf(File::isFile)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun button(label: String, action: () -> Unit) {
            form.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        form.addView(TextView(this).apply {
            text = "On-device speech"
            textSize = 24f
        })
        button("Choose 16 kHz mono WAV") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 1)
        }
        button("Detect speech") { run("VAD") { wav ->
            models.prepareSpeakers()
            val result = PyannoteVad(File(models.speakerDirectory, "segmentation.onnx")).detect(wav)
            "VAD: ${result.size} speech ranges"
        } }
        button("Extract speaker embedding") { run("Embedding") { wav ->
            val file = java.io.RandomAccessFile(wav, "r").use { it.length() }
            // The example requests the first two seconds. The SDK validates the supplied interval.
            require(file > 64044) { "Choose a WAV longer than two seconds." }
            models.prepareSpeakers()
            embeddings = SpeakerEmbeddings(File(models.speakerDirectory, "embedding.onnx")).extract(wav, listOf(SpeechRange(0, 32000)))
            "Embedding: ${embeddings.single().embedding?.size ?: 0} dimensions"
        } }
        button("Cluster speaker embeddings") { run("Clustering") { _ ->
            check(embeddings.isNotEmpty()) { "Extract an embedding first." }
            val result = withContext(Dispatchers.Default) { SpeakerClustering().cluster(embeddings) }
            "Clustering: ${result.speakers} speaker(s)"
        } }
        button("Transcribe with HTP") { run("HTP ASR") { wav ->
            models.prepareAsr()
            val engine = models.asr()
            val started = android.os.SystemClock.elapsedRealtime()
            val result = engine.transcribe(wav)
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            val timings = result.segments.map { it.timing }
            val evidence = "HTP timings (ms): total=$elapsed, load=${result.modelLoadMs}, " +
                "mel=${timings.sumOf { it.melMs }}, encoder=${timings.sumOf { it.encoderMs }}, " +
                "prefill=${timings.sumOf { it.prefillMs }}, decode=${timings.sumOf { it.decodeMs }}, " +
                "decodeGraph=${timings.sumOf { it.decodeGraphMs }}, decodeKv=${timings.sumOf { it.decodeKvMs }}, " +
                "decodeOther=${timings.sumOf { it.decodeOtherMs }}, tokens=${timings.sumOf { it.tokens }}, " +
                "workerPeakRssKiB=${timings.maxOfOrNull { it.workerPeakRssKiB }}"
            "HTP ASR: ${result.executedWindows} windows\n${result.text}\n$evidence"
        } }
        button("Cancel") { job?.cancel() }
        output = TextView(this).apply {
            text = if (audio == null) "Choose a WAV. Models are provisioned in the sample's private speech directory."
                else "Ready"
            setTextIsSelectable(true)
        }
        form.addView(output)
        setContentView(ScrollView(this).apply { addView(form) })
    }

    @Deprecated("Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 1 || resultCode != RESULT_OK || job?.isActive == true) return
        job = scope.launch {
            val file = File(cacheDir, "sample-speech.wav")
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(contentResolver.openInputStream(uri)).use { source ->
                        file.outputStream().use { source.copyTo(it) }
                    }
                }
                audio = file
                embeddings = emptyList()
                output.text = "Audio selected"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                output.text = error.message ?: "Could not import audio."
            }
        }
    }

    private fun run(label: String, action: suspend (File) -> String) {
        if (job?.isActive == true) return
        val selected = audio ?: run { output.text = "Choose an audio file first."; return }
        job = scope.launch {
            output.text = "$label running"
            try {
                output.text = action(selected)
            } catch (cancelled: CancellationException) {
                output.text = "Cancelled"
                throw cancelled
            } catch (failure: Exception) {
                output.text = "$label: ${failure.message ?: "failed"}"
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
