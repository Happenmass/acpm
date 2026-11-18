# acpm

acpm brings agent sessions, tools and on-device AI to mobile apps. Its Android runtime connects ACP agents to host-provided MCP tools. A companion Android service exposes local speech recognition, voice activity detection and speaker analysis through Binder.

[Download the Android APKs](https://github.com/Happenmass/acpm/releases/latest) · [Choose a model profile or export for your device](tools/qwen-htp/export/README.md)

Run local ACP agents inside an Android app through a Kotlin interface for sessions, streaming, approvals, cancellation and host tools. The app defines its UI, model configuration, business data and authorization. The SDK handles the Android process, workspace, task state and tool bridge.

An Activity can subscribe to an application-owned task after recreation. A subsequent app launch marks unfinished tasks interrupted and retains sessions and artifacts. The SDK does not replay a tool call after an interruption. The sample registers one synthetic note tool for Pi and Codex.

## AI SDK console and service

The standalone AI SDK APK opens a runtime console with an overview, Agent sessions and model status. The console reads actual request counts, backend state, reported context use, ACP image events, app PSS and HTP worker RSS. Its Agent view is scoped to the console's own Agent host, and its speech request counts include authorized external clients.

Build the companion APK with `-PspeechHtp` to include the checked Qwen3-ASR, Pyannote and ERes2NetV2 weights. The service extracts them into its own private storage on first use. An Android app can use `dev.androidagent:android-ai-client:0.1.0-dev` to call that service without packaging any speech runtime or weights. The same-signer client supplies read-only WAV descriptors and receives results through Android Binder. See the [speech service build and interface](docs/speech.md).

The public build supports two HTP profiles, architecture-targeted O2 and SM8850-tuned O3. Both keep AR64 prefill at O2. Rebuild the model for another target using the [export scripts and release instructions](tools/qwen-htp/export/README.md).

## Android Use

The standalone AI SDK APK includes an Android Use MCP for the user-selected foreground app. The driver uses Android 14 APIs and requires a device supported by the Agent runtime. Android 17 emulator verification is recorded in [validation](docs/validation.md#android-use-mcp). In **Agents → Open Agent tools**, enable **Allow Android Use for this task**, open Android Accessibility settings and enable **AI SDK Android Use**, then choose the app and confirm the grant. Select a model that accepts both text and images, such as `deepseek-flash`. The SDK shows a task notification with a **Stop** action.

The Agent can open the selected app, get a fresh semantic snapshot and optional screenshot, tap, set text, scroll an element, set an adjustable range, swipe and press Back. The tree includes check and selection states where Android exposes them. Successful actions return a fresh semantic observation when the resulting window is accessible. The Agent can use its snapshot ID for the next action and check the actual UI values before reporting completion. Request a new observation when an image or more recent state is needed.

If the task reaches a different app or a system window, the next explicit request for that window pauses and AI SDK shows its identity. **Allow once** grants the specific foreground window until the task leaves it. The Agent continues with a fresh snapshot. **Deny** blocks that destination for the rest of the task. Protected windows remain unavailable. All access ends when the task ends, the user stops it, the screen locks, or the service disconnects.

Android Use tasks mount the built-in MCP through the existing per-turn MCP connection. Hosts embedding the SDK continue to manage their own business tools. Screen content requested by the Agent may enter the selected model's input and the backend session history. Use a trusted model endpoint and grant only the app and windows needed for the task.

## Development status

The demo and instrumentation APKs build for ARM64 with optional Pi and Codex packs. Both adapters passed isolated tool, workspace and lifecycle checks on the Honor Magic V6 and the Android 17 Google APIs emulator. The Android 17 emulator also completed real model turns through both backends. The tested Android 15 image cannot expose Landlock to the app, so the sample reports the unmet requirement and disables local execution. [Device and test record](docs/validation.md)

The package uses [OpenMA Pi ACP](https://github.com/openma-ai/pi-acp) and [Codex ACP](https://github.com/agentclientprotocol/codex-acp). OpenMA Pi ACP wires client MCP declarations into Pi. The [unscoped pi-acp](https://github.com/svkozak/pi-acp) does not.

## Build the sample

Use JDK 17 or 21 to run Gradle, JDK 17 for the Kotlin toolchain, Android SDK Platform 35 and NDK 28.2.13676358. Builds with runtime packs also require Node 24/npm and Python 3.11+. Install zstd for Codex. Downloads happen at build time and are pinned by the dependency lock and SHA-256 hashes.

```sh
./gradlew :sample:assembleDebug -PagentBackends=pi,codex
adb install -r sample/build/outputs/apk/debug/sample-debug.apk
adb shell am start -n dev.androidagent.sample/.ConsoleActivity
```

Use `-PagentBackends=pi` or `-PagentBackends=codex` to package one backend. Omit it for a diagnostics-only sample. The application's Gradle configuration enables native library extraction with `packaging.jniLibs.useLegacyPackaging = true` so Android can start the packaged executables.

On a supported device, choose **Agents → Open Agent tools**, select a backend, provide an HTTPS model base URL and the model identifier, then run the sample task. Pi expects Chat Completions. Codex expects the Responses API with support for tool namespaces. The provider key stays in the host process and is passed only as an authorization header to the configured upstream. The subprocess uses the SDK's per-run loopback relay.

## Use from Kotlin

Run `python3 tools/check-consumer.py` to stage the artifacts in `build/maven` and verify them in a separate consumer project. Add that Maven repository to your app's Gradle settings, then declare the core and runtime dependencies.

```kotlin
dependencies {
    implementation("dev.androidagent:android-agent-sdk:0.2.1-dev")
    implementation("dev.androidagent:android-agent-backends-pi-codex:0.2.0-dev")
}
```

In your app, define the model endpoint and provide the Kotlin tools allowed for the task.

```kotlin
val runtime = AgentRuntime.get(applicationContext)
val session = runtime.openSession(AgentBackend.Pi, workspace = "notes")
val run = runtime.submit(
    session, "Read note demo", modelEndpoint,
)
```

Supply an `AgentSessionPersistence` and a host-owned files directory on the application's first runtime lookup to retain sessions across process restart. The sample supplies both in its `Application`. Use `submitWithHostMcp` to give a turn a host-owned loopback Streamable HTTP MCP endpoint. The host starts and closes the service and enforces each resource grant.

The console's **MCP** tab shows active and recently finished mounts reported by the sample and same-signer hosts. After `submitWithHostMcp` accepts a turn, a host can call `SpeechServiceClient.observeMcp` in its application scope with the backend name, `binding.port`, `binding.toolNames`, and a suspend callback that awaits the terminal turn status. The SDK process retains up to 12 finished mounts in memory. The console displays the host's reported attachment, declared tools and turn outcome.

## On-device speech

`dev.androidagent:android-ai-speech:0.1.2-dev` provides separate Kotlin entry points for `PyannoteVad.detect`, `SpeakerEmbeddings.extract`, `SpeakerClustering.cluster` and `HtpAsr.transcribe`. VAD and speaker extraction run with the pinned sherpa-onnx 2.0.1 Android runtime, which the host adds as a dependency. The input is a readable 16 kHz mono PCM16 WAV file. Ranges use source-sample offsets. ASR returns recognition-window timestamps and HTP stage timings.

Use `-PspeechHtp` to stage `dev.androidagent:android-ai-speech-htp:0.1.2-dev` with the pinned QAIRT runtime and Qwen worker. Host-provisioned model and runtime paths are passed to `HtpAsr`. The initial HTP target is ARM64 SM8850 with 4 KiB pages. The example supports four independent actions under **Speech tools**. See the [speech setup and validation](docs/speech.md).

Observe `run.state` for streaming, context usage reported by the adapter, and the terminal result. Use `latestMessage` for tasks that return a structured document. The `answer` field retains the full stream. Observe `run.approval` and use `respondToApproval` with the request and option IDs. Responses from a known MCP adapter can include a correlated `hostToolName` for host policy decisions. `cancel()` requests task cancellation. If the Activity is recreated while running, subscribe to `runtime.activeRun`. Use `sessions()` and `history()` to reload state after process death. `clearSession()` returns a replacement with fresh backend context. `deleteSession()` removes the session, and `deleteWorkspace()` is the explicit operation that removes shared artifacts.

The tool handler checks access to the business object identified by its arguments. The sample uses Android's document picker and persistent, revocable read grants to expose a chosen text document as `read_note(id = "document")`. The URI stays in the app. The workspace is separate, shared by sessions assigned the same application-defined workspace identifier. Register `workspaceScriptTool` when the app explicitly grants workspace code execution. Pass an existing workspace environment variable name to keep old scripts compatible.

Pi exposes ACP-provided MCP tools through `tool_search`. Name the host tool in the task instructions and tell the model to discover it before calling it. Codex exposes the tools in its Responses tool namespace.

## Verify

```sh
./gradlew :sdk:assembleDebugAndroidTest -PagentBackends=pi,codex
adb install -r sdk/build/outputs/apk/androidTest/debug/sdk-debug-androidTest.apk
adb shell am instrument -w dev.androidagent.sdk.test/dev.androidagent.sdk.AcpAndroidProbe
```

The instrumentation suite uses synthetic data and a loopback model. On devices with Landlock ABI 6 it exercises isolated model turns and workspace actions. On the Android 15 emulator it separately exercises protocol integration with trusted fixtures. The regular app always requires its local isolation preflight. The [physical device commands](docs/validation.md#reproduce-on-a-physical-device) also test the sample UI.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). The backend pack includes the pinned upstream package and runtime notices.
