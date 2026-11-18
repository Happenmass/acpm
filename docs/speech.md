# Independent speech operations

| Entry point | Input | Result |
| --- | --- | --- |
| `PyannoteVad.detect` | WAV, pyannote segmentation 3.0 ONNX, duration and gap options | Speech ranges on the source sample timeline |
| `SpeakerEmbeddings.extract` | WAV, ERes2NetV2 ONNX, explicit ranges | One observation per range with a vector when the model has enough input |
| `SpeakerClustering.cluster` | Observations and clustering policy | Recording-local labels, including unknown labels, and counts |
| `HtpAsr.transcribe` | WAV or explicit sample ranges, pinned Qwen export and QAIRT runtime | Recognition windows, text, model-load and per-window timings |

All audio entries accept 16 kHz mono PCM16 RIFF WAV. The host supplies the models as readable files. VAD uses weighted classification from overlapping 10-second windows. Embedding reads at most six seconds from the start of each supplied range. Callers can supply clean speech ranges and reuse observations for clustering.

### HTP inference server

The SDK starts one private QNN subprocess per app process on the first non-silent window. The server serializes windows of up to 24 seconds and shares the loaded QNN graphs across decoder clients that use the same model and runtime paths. Keep those files stable while serving requests. Changing the configuration starts a new worker. Closing a decoder deletes its temporary WAV, and the worker remains available for subsequent requests.

The idle interval starts after the last model request completes. After one hour by default, the worker releases its model, QNN contexts and buffers. The same process waits for the next request and then loads the model again. `modelIdleTimeoutMs` lets the host choose a different interval. An interrupted exchange discards the worker so the next request starts with a clean pipe. Android may reclaim the app process, and its next model request starts a new worker.

`modelLoadMs` is zero for a cache hit and `null` if no window ran. `decodeMs` starts after Prefill and includes the autoregressive QNN calls, KV writes and host-side token handling. `workerPeakRssKiB` is the worker's process-lifetime RSS high-water mark. Each request uses its own audio and recognition position.

## HTP export and release profiles

The SDK packages AR64 at O2 and switches between 512- and 1,024-position AR1 graphs sharing full-size KV buffers. Choose `-PhtpProfile=o2` for the architecture-targeted O2 distribution or `-PhtpProfile=sm8850-o3` for the SM8850-tuned decoder. The bundled O2 target is Hexagon V81. See the [checkpoint conversion, target selection and two-APK build](../tools/qwen-htp/export/README.md).

## Companion service APK

Build both the app and its isolated instrumentation.

```sh
./gradlew :sample:assembleDebug :sample:assembleDebugAndroidTest -PsampleFixture -PspeechHtp -PagentBackends=pi,codex
```

The build uses the cached sherpa-onnx Android AAR and the pinned QAIRT SDK. The APK also packages all model files and their notices. `tools/stage-speech-models.py` verifies the shared cache against `tools/qwen-htp/models.json` and `tools/speech-models/speakers.json`. On first use the service checks each asset and atomically publishes the extracted group in its own `noBackupFilesDir/speech-models`. HTP model unload releases the in-memory resources while keeping the installed files available for the next load.

A host uses the Maven artifact `dev.androidagent:android-ai-client:0.1.0-dev`. Build it with `./gradlew :client:publishClientPublicationToDevelopmentRepository`. The manifest declares package visibility for the chosen service package and requests its `<package>.permission.SPEECH` signature permission. Sign the SDK APK and authorized client APKs with the same Android signing certificate.

```kotlin
val speech = SpeechServiceClient(applicationContext, "dev.androidagent.sample")
val status = speech.refresh()
val window = speech.transcribeWindow(wavFile)
val speakers = speech.diarize(recordingWav)
```

The versioned Binder interface passes read-only file descriptors for 16 kHz mono PCM16 WAV. Windows are up to 24 seconds. Diarization accepts recordings up to 80 minutes and returns recording-local labels. The service stages each descriptor into a private temporary file and removes it after the request. It supports cancellation and cancels work when a caller dies. Prompts, transcription text and raw audio do not appear in the console.

Provision only the synthetic audio fixture for the UI probe on a build with bundled weights. A diagnostics-only build can still use manually staged speaker weights under `files/speech`.

| Path relative to `files/speech` | File |
| --- | --- |
| `fixture.wav` | Synthetic mono 16 kHz PCM16 audio, with at least two seconds of clear speech |

Run the UI probe on an emulator with the serial selected explicitly.

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -e stage speech \
  dev.androidagent.sample.fixture.test/dev.androidagent.sample.SampleProbe
```

The emulator probe checks the actual VAD, embedding and clustering results and expects the HTP device diagnostic. On a compatible phone with the same fixtures and model setup, add `-e requireHtp true` to require HTP graph execution and a nonempty transcription. Use `-e stage htp-benchmark` with the 8.802-second bilingual fixture to run three cold/warm pairs, compare transcripts and exercise unloading with a five-second idle interval.

The Agent example provides its own atomic session snapshot storage and a per-turn Streamable HTTP MCP server from the host `Application`. The SDK handles session recovery, Agent launch and turn events.

## Development validation

- Android 15 ARM64 sample with synthetic speech detected a speech range, produced a 192-dimensional speaker embedding and clustered it into one speaker. The HTP screen reported `UNSUPPORTED_DEVICE`.
- Android 17 ARM64 ran both Agent backends. Each backend called the sample's host-owned MCP server and returned a synthetic note obtained through the tool during real model inference. The Activity recreation and foreground service checks also passed.
- Sound Software retains its MCP server, session persistence, window planner and checkpoints. Its SDK client calls the companion process for Qwen HTP transcription and speaker analysis.
- On HONOR Magic V6 (SM8850, Android 17, 4 KiB pages), three warm sample calls transcribed an 8.802-second bilingual fixture in a median 1.864 s. Warm calls in Sound Software took a median 1.872 s. The SDK sample verified two automatic unload/reload cycles in the same worker PID, and the full Sound Software pipeline passed on the same WAV. Peak worker RSS was about 718 MiB across the benchmark runs.

- The 24-second SDK worker now selects its HTP core and bus vote at each recognition window. On the Magic V6 the higher vote reduced AR1 latency while the phone reported NONE or LIGHT thermal status. See the [controlled comparison with the 8-second OnSpeak47 export](htp-asr-magic-v6-2026-11-18.md).
