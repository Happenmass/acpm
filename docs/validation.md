# Android baseline verification

Date: 2026-11-15.

- Android 15 emulator: ARM64 Pixel Tablet, API 35, 4 KiB pages. The running kernel reports `CONFIG_SECURITY_LANDLOCK` unset. The system shell returned `ENOSYS` for the Landlock capability query and the app UID received a seccomp trap. The sandbox launcher converts that trap into a distinct exit code. A sample UI assertion verified the diagnostic and disabled Run task.
- ACP Kotlin 0.34.0 initialized against a deterministic peer on Android. The probe observed two text chunks, a prompt terminal result and session load after restarting its peer. The transitive JVM logging adapter required an explicit SLF4J API runtime dependency.
- The app UID connected to the Kotlin tool endpoint, saw one synthetic tool, invoked it once, and was denied after revocation. Requests without the ephemeral endpoint token were also denied. This exercise uses the private bridge protocol.
- A persisted running task was read by a fresh store instance as interrupted. It retained its native session identifier.
- A test sent MCP requests to the packaged stdio worker, which discovered and invoked the same Kotlin note tool. Its second call was rejected after revocation. The adapter initialization and the MCP dispatch tests are separate.
- The test APK extracted its embedded JavaScript bundle and started OpenMA Pi ACP 0.1.8 and Codex ACP 1.1.5 using its own extracted ARM64 Node launcher. Both identified themselves over ACP v1. The Codex adapter launched the pinned Codex 0.165.0 binary. The probe supplies `TMPDIR` because the Pi adapter creates a temporary scope and Android apps cannot write to `/tmp`.
- Development-host initialization can be reproduced with `npm ci --ignore-scripts --omit=optional` and `python3 check-adapters.py` inside `tools/backend-probe`. It uses temporary homes, no model credentials and no prompt.

Fresh debug builds measured 79.2 MiB for Pi, 161.8 MiB for Codex, and 190.6 MiB for the two backends together. Each package was inspected for its bundled entries. The dual package shares the Android Node runtime. A clean `:sample:clean` was required before measuring because incremental APK packaging can retain space from the previous variant.

## Android 17 emulator

Google APIs ARM64 system image `arm64-v8a-37.0_r08`, Android API 37, 4 KiB pages and kernel `6.12.81-android16-6`. This image enables Landlock, and the sample and SDK ran the sandbox launcher as app UIDs under the stock image policy. Run the SDK instrumentation with `-e requireIsolation true` to make isolation a prerequisite for passing.

- The SDK integration probe passed with both packaged backends, including the isolated artifact handoff, workspace boundary, cancellation and detached-child cleanup.
- Both sample UI runs survived Activity recreation through their foreground services. Both finished with the screen noninteractive and keyguard showing after enabling the emulator's swipe lock screen. The force-stop and next-launch fixture retained its completed artifact and marked the active turn interrupted.
- With genuine Qwen3-4B-Instruct-2507 inference hosted on a Mac by `mlx-openai-server` 1.8.1, Pi and Codex each discovered and invoked an app-registered Kotlin tool once and returned its per-run marker. Both backends also returned the synthetic note codename through the sample UI. The test gateway flattened the Codex `namespace` definitions for the inference server and restored the namespace on returned calls. The inference server generated the calls and final answers.
- The real Codex run revealed approval updates that contained a call identifier and no title. The SDK now correlates those updates with the earlier tool notification, so the host can display the title and grant a matching approval.
- Both adapters also populated the SDK turn's context usage during the real-model run. The context limit reflects the adapter's own metadata.
- Compatibility depends on the tested system image, kernel configuration and app policy.

The Sound Software Android app integrated the published development artifacts after the sample passed. It uses `latestMessage` to validate structured replies, `hostToolName` for per-invocation authorization, and a workspace environment alias to keep existing scripts working. Its upgrade fixture installed an APK built from the previous app source and updated it with the same signing key. The app retained the existing database and model settings, and both backends accessed a file in the migrated workspace. An adversarial model fixture sent an out-of-scope recording ID through each adapter and received a Kotlin scope denial.

## Android Use MCP

On 2026-11-18, Pi and Codex each ran an Android Use task with `deepseek-flash` on a dedicated Google APIs ARM64 Android 17 emulator with 4 KiB pages and the stock 6.12.81 kernel. The independently installed test APK displayed a fresh five-character code on a Canvas. The code was absent from the accessibility tree. Each Agent requested the screenshot via MCP, entered the code via the Android accessibility service and observed the result. Instrumentation separately verified that the test APK displayed `Code accepted`.

A separate build of the standalone debug APK included Pi, Codex, bundled speech weights and Android Use. It installed and cold-launched the AI SDK console on a clean Android 17 emulator. This check covered packaging and launch.

| Android Use check | Observed result |
| --- | --- |
| Pi through Chat Completions | DeepSeek received the screenshot as an image and the test APK accepted the entry. |
| Codex through Responses | DeepSeek received the screenshot as an image in the tool result and the test APK accepted the entry. |
| Native input | `ACTION_SET_TEXT` wrote the exact Chinese fixture text and accessibility inspection read it back. |
| Snapshot lifetime | The previous observation and the already-used snapshot both returned `stale_snapshot`. The current value stayed intact. |
| App scope | Observe and type returned `wrong_app` after a switch to Home. The driver reopened the granted test app and rejected the old snapshot. |
| Protected window | Both image and tree-only observations returned `protected_window` under `FLAG_SECURE`. |
| Revocation | Both an image observation and a write returned `revoked` after the in-process grant was closed. |

Pi needs the model's `input` metadata to include `image`. Codex needs a model catalog with `input_modalities` including `image`. Codex prioritizes `structuredContent` when converting MCP results to Responses input. Visual observations carry their JSON metadata and JPEG in the same standard MCP `content` array.

Build both fixture APKs, then install them on a dedicated Android 17 emulator. The test runner temporarily enables the fixture build's accessibility service and restores the previous setting. Use a host loopback relay that forwards Chat Completions and Responses to the configured model and injects its authorization header on the host. Follow [the project instructions](../AGENTS.md#local-model-acceptance) for the macOS credential lookup.

```sh
./gradlew :sample:assembleDebug :sample:assembleDebugAndroidTest -PsampleFixture -PagentBackends=pi,codex
adb -s "$EMULATOR_SERIAL" install -r sample/build/outputs/apk/debug/sample-debug.apk
adb -s "$EMULATOR_SERIAL" install -r -t sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk
runner=dev.androidagent.sample.fixture.test/dev.androidagent.sample.SampleProbe
adb -s "$EMULATOR_SERIAL" shell am instrument -w -e stage android-use-boundaries "$runner"
for backend in pi codex; do
  adb -s "$EMULATOR_SERIAL" shell am instrument -w -e stage android-use -e backend "$backend" \
    -e gatewayPort "$GATEWAY_PORT" -e model deepseek-flash "$runner"
done
```

### Real-app probes

After the Android Use merge, the two random-code runs, permission boundary suite and existing ACP probe passed on Android 17 with the local MCP console changes present. Both backend configurations again used DeepSeek Flash.

The first real-app check adapts [AndroidWorld's ClockTimerEntry](https://github.com/google-research/android_world/blob/main/android_world/task_evals/single/clock.py) to the Google Clock shipped on the Android 17 emulator. It verifies the exact draft duration and the absence of a running or paused timer UI independently of the Agent's answer.

| Backend | Observed draft | Outcome | Android Use calls | Elapsed |
| --- | --- | --- | --- | --- |
| Pi | 01:37 | Passed, timer stood at setup | 10 | 18.2 s |
| Codex | 02:46 | Passed, timer stood at setup | 15 | 19.0 s |

These are individual runs with different inputs. Pi also made one tool discovery call. The traces show repeated observations between keypad actions, which is expected with the current single-use snapshot design. Measure that cost on longer tasks before changing the action contract.

Use the same installed fixture APKs and gateway as above.

```sh
for backend in pi codex; do
  adb -s "$EMULATOR_SERIAL" shell am instrument -w -e stage android-use-clock-timer -e backend "$backend" \
    -e gatewayPort "$GATEWAY_PORT" -e model deepseek-flash "$runner"
done
```

The six-app evaluation adapts the following [AndroidWorld tasks](https://google-research.github.io/android_world/task_list.html).

| Task | Test case | Independent verifier | What it tests |
| --- | --- | --- | --- |
| `ContactsNewContactDraft` | Enter a synthetic Chinese name and test number, choose a phone label and leave the draft open. | The editor shows all four values. The contact store count stays unchanged. | Unicode, multiple editable controls, selection and honoring a no-save request |
| `MarkorCreateNote` | Save a Markdown note with a Chinese title, blank line and two list items. | Read the saved file and compare its name and exact UTF-8 content. | Multi-line text, keyboard focus and durable write verification |
| `SimpleCalendarAddRepeatingEvent` | Add a half-hour weekly practice event to a local test calendar. | Check the stored first occurrence, duration, recurrence and exact title. | Date and time pickers, scrolling and a longer multi-step form |
| `FilesMoveFile` | Move one seeded file out of Downloads into a named nested test folder. | Source is absent. Target name, path and hash match the seeded file. | Duplicate labels, directory navigation, selection affordances and recovery across dialogs |
| `OsmAndMarker` | Find a seeded place using an offline map and add a marker. | Read back the correct marker and coordinates within the fixture's tolerance. | Search, map rendering and screenshot-based localization |
| `MarkorTranscribeReceipt` | Read a synthetic receipt in Gallery and transcribe it to a Markor CSV. | Decline the handoff to Markor and assert that the message has no image or editable elements and creates no CSV. | Cross-app authorization and visual transcription |

For each supported task, pin the app version, locale and starting state. Run three seeded variants per backend and score on independent app state. Keep tool calls, elapsed time and the first blocking condition. Treat an expected authorization denial as a separate boundary check.

### Six-app evaluation on Android 17

On 2026-11-18 the Android Use fixture APK ran Pi and Codex with DeepSeek Flash on the same dedicated ARM64 Android 17 emulator. Both received the same synthetic task inputs. The verifier reset fixture outputs between backends. It checked contact editor fields and provider rows, file content and hashes, and SQLite rows outside the Agent process.

| App | Fixed input | Independent check |
| --- | --- | --- |
| Google Contacts 4.74.28.876719826 | 李, 星河, 2025550147, Work. Leave the editor open. | All four values present with Save still available. No rows added to the contacts provider. |
| Markor 2.10.9 | 验收清单.md with heading 交接计划, a blank line, and the two items 核对录音 and 更新日历. | Exact filename under Documents/Markor and exact UTF-8 body, allowing one final newline. |
| Simple Calendar Pro 6.22.2 | 练习复盘, 2026-12-04 from 10:00 to 10:30 in Asia/Taipei, every Friday with no end date. | Exact title and timestamps, weekly interval 604800, Friday mask 16, limit 0 and local calendar source. |
| Android Files 17 | Move android-use-invoice.txt from Downloads to Documents/SDK Bench/Archive. | Source absent, destination hash equals the seeded file hash. |
| OsmAnd 4.6.13 | Add an active marker for Ruggell using the offline Liechtenstein map. | Exactly one added active marker within 0.001° on both axes of 47.23976° N, 9.5262837° E. |
| Simple Gallery Pro 6.28.1 and Markor | Read sdk-receipt.png and attempt a Markor CSV with only the Gallery grant. | Authorized Gallery screenshot works. With Markor foreground both screenshot and a write using the prior Gallery snapshot return `wrong_app`. No CSV is created. |

The four third-party apps and offline map are the fixtures defined by [AndroidWorld's app setup](https://github.com/google-research/android_world/blob/main/android_world/env/setup_device/apps.py). Google Contacts and Android Files are built into this system image. Evidence is in `build/reports/android-use-real/`, including APK and fixture hashes, independent readbacks, and tool traces with image data removed.

| Task | Pi | Codex | Observed point of friction |
| --- | --- | --- | --- |
| Contacts draft | Passed after setup | Passed after setup | Both cold launches were blocked by the system notification permission dialog. The test operator declined it. Both later hit a non-clickable Work label and recovered by operating the row. No contact was saved. |
| Markor note | Passed on verifier rerun | Failed | Pi saved the exact file. Codex spent 125 Android Use calls navigating Go to, Notebook and Documents without creating the file before the 180 s limit. |
| Repeat event | Passed | Passed | Both databases held the exact event and recurrence. Pi issued 80 Android Use calls and Codex issued 50. |
| Move file | Passed | Passed | Both used Select all in the one-file Downloads fixture, then navigated the destination picker. Codex also tried a zero-length swipe to select the item and received `invalid_argument`. |
| OsmAnd marker | Failed | Failed | The first runs could not find Ruggell from their search origin. On the paired rerun with the same Bendern viewport and GPS seed, both found Ruggell Village. The result title was not a native click target. Coordinate retries were repeatedly rejected as stale. Both reached the 180 s limit and neither database contained an active marker. |
| Cross-app receipt | Boundary passed | Boundary passed | With Gallery as the only grant, bringing Markor forward caused both read and write to return `wrong_app`. Independent file checks found no CSV in the notebook or Downloads. |

The first Markor Pi verification used an incorrectly quoted ADB file read. Its independent exact-content verdict comes from the rerun. Calendar scoring uses the actual Friday bit mask of 16 from [the AndroidWorld validator](https://github.com/google-research/android_world/blob/main/android_world/task_evals/single/calendar/calendar_utils.py), applied to the captured database rows.

In the original build, a tap consumed the snapshot before checking action eligibility. Unrelated content refreshes could also invalidate coordinate retries. For the next Files case, seed decoys so selecting all cannot satisfy the move.

Use the fixture APKs and gateway described above. Set up the selected App and fixed input outside the Agent, then run the probe. Use a dedicated emulator for the state reset.

```sh
adb -s "$EMULATOR_SERIAL" shell am instrument -w -e stage android-use-real-app \
  -e case Contacts -e backend pi -e gatewayPort "$GATEWAY_PORT" \
  -e model deepseek-flash "$runner"
```

Case names are `Contacts`, `Markor`, `Calendar`, `Files`, `OsmAnd`, and `Receipt`.

### List row and coordinate tap regression on Android 17

An observed label can now include `tapTargetId` for its nearest eligible clickable ancestor. Tapping the label's own ID resolves both nodes again in the authorized window. A rejected semantic tap or unscheduled gesture keeps the snapshot. A successful native action or dispatched gesture consumes it. Coordinate taps compare a clipped 49 × 49 pixel neighborhood in the observed image coordinate space with a fresh window capture. Package, window and overlay checks still gate dispatch. Swipe keeps its existing screen revision check.

The `android-use-boundaries` probe exercised a non-clickable label inside a clickable row, a rejected semantic tap followed by a coordinate retry using the same snapshot, a change outside the tap area, rejection after the tap area changed, and replay of a consumed snapshot. The same probe verified app scope, protected windows, and revocation. Evidence is in `build/reports/android-use-tap/`.

Pi and Codex used DeepSeek Flash, the same prepared Android 17 fixtures and the same prompts from the six-app evaluation.

| Case | Pi | Codex | Independent result |
| --- | --- | --- | --- |
| Contacts Work | Passed, 19 calls | Passed, 25 calls | Both tapped the `Work` text by ID through `tapTargetId`. All draft fields matched, and the contacts provider had no new rows. |
| OsmAnd Ruggell | Passed, 27 calls | Passed, 36 calls | Each run added exactly one active marker at 47.2397575° N, 9.5262871° E. The search result still required a coordinate tap. Pi reobserved after the tap region changed. Codex retried with the same snapshot after the rejected semantic tap. |
| Gallery → Markor receipt | Boundary passed | Boundary passed | With Markor in front and only Gallery authorized, screenshot, text entry and coordinate tap returned `wrong_app`. The file check found no new CSV. |

### Window handoff on Android 17

On leaving the initially authorized app, the next MCP request for a different foreground app or system window waits on the local AI SDK consent card, for up to 30 seconds. The card names the package and available window title. Approval binds the current package and window ID until the task leaves it. If the window changes during approval, the request expires. The new scope starts with a fresh observation. A denied request responds with `authorization_denied` and withholds the destination's content. The same destination package remains blocked for that turn.

The `android-use-handoff-boundaries` probe clicks the actual consent card. It checks that the triggering call pauses, an old coordinate is rejected after approval, the destination returns a new snapshot, returning to the original app ends the handoff, a changed destination invalidates approval, and denial blocks image and action requests. Evidence is in `build/reports/android-use-handoff/`.

Pi and Codex ran on the same Android 17 emulator with DeepSeek Flash. The test operator clicked the actual SDK consent buttons and the fixture verified the result independently of the model.

| Case | Pi | Codex | Independent result |
| --- | --- | --- | --- |
| `HandoffBrightness` | Passed | Passed | Allowed the one System UI brightness window. Both display readbacks equaled the device minimum of `0.0`. |
| `HandoffSms` | Passed | Passed | Allowed the Google Messages conversation. ABC's compose field contained exactly `Nice to meet you`, and no outgoing SMS rows were added. |
| `Receipt` | Boundary passed | Boundary passed | Denied Markor. Both models received `authorization_denied` with no destination image. Gallery remained available and disk checks found no new CSV. |

The first brightness trial reopened the dialog after stopping just above the minimum. The test operator had only handled the first dialog. The recorded rerun handles a new confirmation on each reopening and verifies the exact display minimum. The Android 17 build and the original scope and revocation probes also passed.

## Physical device

Honor Magic V6 (PNM-AN10), Android 17/API 37, ARM64, 4 KiB pages, Landlock ABI 6 under the app UID.

| Check | Observed result |
| --- | --- |
| Model and tool routing | Both pinned adapters completed real ACP→MCP→Kotlin calls against a loopback model fixture. Pi discovered the note with tool search. Codex requested permission before invoking the Kotlin tool. Each adapter resumed its original native session in a new process without invoking the tool again. |
| Approval and secret handling | Codex allow invoked exactly once. Reject and cancel invoked zero times. The fixture checked the configured model, route, and synthetic bearer header. The key was absent from the subprocess environment, request body, and captured stderr. |
| Model error reporting | Both backends showed HTTP 401 as a failed model request. The relay discarded the upstream error body. |
| Workspace handoff and isolation | Through the public SDK interface, Codex used the isolated JavaScript tool to write a file and Pi read it in a different session with the same workspace grant. A narrow launcher check denied reading outside the workspace and denied script TCP access. |
| Session and workspace cleanup | After clearing the Pi session, the first model request from the replacement omitted the old tool result. Deleting the Codex session preserved the shared artifact. An explicit workspace deletion removed it. |
| Cancellation and child cleanup | A second task was rejected as busy, the active task reached a persisted cancelled state, and scratch files were removed while the artifact survived. The launcher also terminated and reaped a test child that created a separate process group. |
| Sample activity and foreground service | Pi and Codex each ran from the sample UI. The foreground service remained active during Activity recreation, and stopped after task completion. The sample uses the host's user-started service and notification. |
| Screen lock | Both backends completed through the sample's foreground service with the display noninteractive and the keyguard reported as locked. The probe released the fixture response only after observing the lock. |
| Android process death | ADB force-stopped only the isolated sample fixture after a live model request. Its next launch marked the previous turn interrupted, kept the workspace artifact and removed abandoned scratch. |

The Codex App Server checks provider authentication before receiving thread options. The SDK writes its loopback provider into the backend home before startup. The launcher uses a child supervisor to reclaim detached descendants. It permits Codex to create an MCP process group and only signals children whose kernel-reported parent is the launcher.

The model fixture is deterministic. The pinned backends, MCP worker, sandbox, Kotlin tool code and Android lifecycle in the table actually ran. The physical device reached the configured hosted gateway, which rejected inference due to upstream authentication. The two successful real-model checks above ran on the Android 17 emulator. Feedback from independent developers remains pending.

An independent Gradle project resolved the two staged Maven artifacts with only the sample source. Its regular APK built in 6.9 seconds with cached dependencies. Its separate synthetic fixture ran Pi and Codex on the Magic V6, observed each task survive Activity recreation, and saw the foreground service stop when the task finished.

### Startup and memory sample

| Backend | App cache | First model request | Peak sampled process-tree RSS | Max processes |
| --- | --- | ---: | ---: | ---: |
| Pi | Cleared | 3.578 s | 461.6 MiB | 4 |
| Pi | Retained | 0.962 s | 453.7 MiB | 4 |
| Codex | Cleared | 4.726 s | 532.3 MiB | 5 |
| Codex | Retained | 1.092 s | 526.3 MiB | 5 |

One run per entry on the Magic V6. The first-request timer starts before the probe enters the prompt and taps Run. It includes UI and adapter startup and ends when the loopback fixture receives the request. Both trials start new app processes. The cleared trial resets the synthetic app's data and staged runtime; the retained trial reuses them. RSS sums the sample and descendant processes. Shared pages can contribute more than once. This is a sampled RSS peak; real model latency, PSS, and low-memory behavior need separate measurements.

## Reproduce on a physical device

Use the SDK instrumentation command from [the README](../README.md). Build the sample fixture under its own package and run the UI and interruption probes.

```sh
./gradlew :sample:assembleDebug :sample:assembleDebugAndroidTest -PagentBackends=pi,codex -PsampleFixture
adb -s "$PHONE_SERIAL" install -r sample/build/outputs/apk/debug/sample-debug.apk
adb -s "$PHONE_SERIAL" install -r sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk
adb -s "$PHONE_SERIAL" shell am instrument -w dev.androidagent.sample.fixture.test/dev.androidagent.sample.SampleProbe
adb -s "$PHONE_SERIAL" shell am instrument -w -e backend codex dev.androidagent.sample.fixture.test/dev.androidagent.sample.SampleProbe
python3 tools/check-phone-interruption.py "$PHONE_SERIAL"
adb -s "$PHONE_SERIAL" shell input keyevent KEYCODE_WAKEUP
adb -s "$PHONE_SERIAL" shell am instrument -w -e stage lock -e backend pi dev.androidagent.sample.fixture.test/dev.androidagent.sample.SampleProbe
adb -s "$PHONE_SERIAL" shell input keyevent KEYCODE_WAKEUP
adb -s "$PHONE_SERIAL" shell am instrument -w -e stage lock -e backend codex dev.androidagent.sample.fixture.test/dev.androidagent.sample.SampleProbe
python3 tools/profile-phone.py "$PHONE_SERIAL"
python3 tools/check-consumer.py --serial "$PHONE_SERIAL"
```

The test model uses loopback HTTP permitted only by the sample's debug network policy. The sample's optional foreground service follows the [Android special-use service declaration](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

### Remote model acceptance

Supply an authorized model configuration in the regular sample and ask it to call `read_note` for `demo`. For automated checks, install the dual-backend SDK instrumentation APK from the README and the sample fixture APKs above. Point the runner at a host-side loopback endpoint that serves Chat Completions and Responses at `/v1/` and accepts requests over its local listener. Where upstream authentication is needed, configure it in the gateway. The runner creates and removes a temporary USB reverse mapping.

```sh
python3 tools/check-remote-model.py "$PHONE_SERIAL" "$HOST_PORT" --pi-model "$PI_MODEL" --codex-model "$CODEX_MODEL" --probe sdk
python3 tools/check-remote-model.py "$PHONE_SERIAL" "$HOST_PORT" --pi-model "$PI_MODEL" --codex-model "$CODEX_MODEL" --probe sample
```

The SDK probe requires one successful Kotlin tool invocation and checks the answer for a marker generated for that run. The sample probe submits through the sample UI and checks for the codename held in its built-in synthetic note.

Use `--emulator` with an Android Emulator serial to reach the host endpoint at `10.0.2.2`. For an inference server with flat function tools on its Responses route, start the local test gateway and use its listening port for both probes.

```sh
python3 tools/responses-test-gateway.py "$INFERENCE_PORT" "$HOST_PORT"
python3 tools/check-remote-model.py "$EMULATOR_SERIAL" "$HOST_PORT" --emulator --pi-model "$PI_MODEL" --codex-model "$CODEX_MODEL" --probe sdk
python3 tools/check-remote-model.py "$EMULATOR_SERIAL" "$HOST_PORT" --emulator --pi-model "$PI_MODEL" --codex-model "$CODEX_MODEL" --probe sample
```
