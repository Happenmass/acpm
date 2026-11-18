# Android Use MCP on AndroidLab tasks

2026-11-18, SDK `04c4607`. This comparison uses eight operation tasks from AndroidLab `4bfe108`, covering Clock, Contacts, Settings, and the Contacts-to-Messages handoff. AndroidLab publishes 138 tasks and documents an API 33 AVD for its Mac run. This run uses the same eight instructions on an Android 17 / API 37 ARM64 emulator with local fixtures and independent Android 17 checks. [AndroidLab](https://github.com/THUDM/Android-Lab/tree/4bfe108cf3883b710e8e5c6fba288161c830e504) · [Mac AVD setup](https://github.com/THUDM/Android-Lab/blob/4bfe108cf3883b710e8e5c6fba288161c830e504/docs/prepare_for_mac.md)

## Method

- Both MCP servers used one common host runner, DeepSeek Flash through Chat Completions with thinking disabled and temperature 0, one execution per task, up to 44 calls and a 140-second soft deadline. The runner alternated server order by task, retained only the latest screenshot in the model context, and reset test data before each attempt. Independent state checks determined the score. Tool traces explain the observed failures.
- SDK calls went to the committed Android Use driver over its regular MCP server. The test-only bridge in a detached checkout opened a single-app grant for each task. Mobile MCP 1.0.18 used its Android mobilecli driver. The common runner exposed 12 interactive Mobile MCP tools and bound the emulator ID outside the model. It restricted explicit app launches to the task packages and allowed normal system UI transitions. The host's fixture preparation and verification were outside either model's tool set.
- Clock 8.7, Contacts 4.74.28.876719826, Settings 17, and Messages `messages.android_20260306_02_RC09.phone_dynamic` ran on the same device. Independent checks read the alarm and contacts databases, Settings values and brightness, and the final timer, city and compose screens. The SMS test also checks the outgoing SMS rows.
- Raw results, tool traces with image bytes omitted, and the runner and test-only bridge patch are in `build/reports/android-use-androidlab/`. The first preflight found that Mobile's on-device helper outlived the local MCP process and held the Android UI automation connection. The scored run terminated that helper after each Mobile run to allow the independent UI dump and the next SDK run to connect.

## Results

The SDK passed 4 of 8 tasks in 197 MCP calls. Mobile MCP passed 7 of 8 in 159 calls. On the three tasks both passed, the SDK used 86 calls and Mobile used 68. These are single-run results for the selected tasks.

Each entry gives the outcome and number of MCP calls. The checks use the request text and persisted settings, including exact digits for the synthetic phone number. The upstream Contacts judge uses old UI labels and an email domain that differs from its task prompt. [Task config](https://github.com/THUDM/Android-Lab/blob/4bfe108cf3883b710e8e5c6fba288161c830e504/evaluation/config/contacts.yaml#L9) · [Upstream judge](https://github.com/THUDM/Android-Lab/blob/4bfe108cf3883b710e8e5c6fba288161c830e504/evaluation/tasks/contacts/contacts.py#L37)

| Task | SDK | Mobile MCP | Independent readback |
| --- | --- | --- | --- |
| `clock_2` — 6:45 alarm, Argon, vibration off | 34 calls, passed | 33 calls, passed | Both databases contained an enabled 06:45 alarm with vibration off and a ringtone URI for Argon. |
| `clock_6` — weekend alarm | 22 calls, failed | 30 calls, passed | The SDK run saved the right label and weekend mask at 22:00. The trace shows that the model confirmed the time picker before selecting 30 minutes. Mobile saved 22:30. |
| `clock_15` — London and Barcelona | 28 calls, passed | 24 calls, passed | Both cities appeared on the world clock in both runs. |
| `clock_18` — unstarted 1h15m timer | 24 calls, passed | 11 calls, passed | Both final screens showed the requested duration and Start. The SDK run re-entered the timer after leaving the screen once. |
| `contacts_2` — exact contact details | 33 calls, passed | 30 calls, failed | Both saved the name and Work email. Both model calls supplied `12345678`. The contacts provider stored exactly that with SDK element text replacement, and stored `+112345678` with Mobile's focused input path on this fixture. |
| `contacts_9` — unsent SMS draft | 14 calls, blocked | 10 calls, passed | The SDK returned `wrong_app` after the handoff to Google Messages. The Mobile run left the exact message in ABC's compose field. Both outgoing SMS queries returned zero rows. |
| `setting_5` — show battery percentage | 13 calls, failed | 9 calls, passed | The Mobile run tapped Battery in Settings and checked the toggle. The SDK model swiped past Battery, entered Search Settings, then received `wrong_app`. The stored setting stayed off. |
| `setting_8` — set brightness to 0% | 29 calls, failed | 12 calls, passed | The SDK run opened the system slider and received `wrong_app`. Mobile dragged the slider to the left endpoint. The display service read `0.0`. |

Direct MCP follow-up reproduced the two Settings transitions. Search opened `com.google.android.settings.intelligence`, and the brightness row opened `com.android.systemui.settings.brightness.BrightnessDialog`. Both returned `wrong_app` under the Settings-only grant. The SDK also successfully enabled battery percentage through the in-app Battery row during the direct follow-up. The detached build passed the click and permission fixture checks before cleanup.

## Open-source reference points

| Server | Relevant capabilities |
| --- | --- |
| Mobile MCP | Live comparison. Host-controlled device with foreground-app query, text input to the focused element, screenshots with a coordinate mapping, reference-based taps, drag, long press, hardware buttons, and batch commands. The MCP server is Apache-2.0 and the tested mobilecli dependency uses FSL-1.1-ALv2. [Pinned tool registration](https://github.com/mobile-next/mobile-mcp/blob/5b547587b53b28eec4edc447a735d9b0daea76a9/src/server.ts) · [mobilecli](https://github.com/mobile-next/mobilecli) |
| Appium MCP | Source comparison. Appium/WebDriver sessions offer accessibility ID, Android UiAutomator and XPath selectors, `scroll_to_element`, drag and W3C touch actions. [Pinned Appium tools](https://github.com/appium/appium-mcp/blob/154ff119347d2d7d05bd537080fe5bf341b3fcba/README.md#available-tools) |
| Android Remote Control MCP | Source comparison. On-device AccessibilityService and MCP over HTTP, with optional remote pairing via OAuth, configurable HTTPS and on-device redaction. [Pinned feature list](https://github.com/danielealbano/android-remote-control-mcp/blob/2e2582fa9ec6f88aa60d930abf29d9893748933d/README.md#features) |

The SDK exposes an image and semantic tree in one observation. Element and screenshot coordinates bind to the active window and snapshot, and coordinate taps check the content near the intended touch. The product grant limits inspection and actions to the selected app and ends with the turn.

## Gaps to address

1. Support a task-scoped handoff with explicit authorization for the destination app or system window. Settings search, the brightness slider, and the message composer crossed authorization boundaries. The slider and SMS result depended on acting in the destination. Suspend when authorization is needed, retain the pending task, and resume with a fresh observation once the user approves the specific destination.
2. Expose control state and add semantic scrolling and range adjustment where the Android node supports them. The agent skipped the minute selection in the weekend alarm, and a long swipe overshot Battery. Mobile's drag and Appium's bounded scroll-to-element show useful alternatives for interaction. Include `checked`, `selected`, `focused`, and range values in the observation so the agent can verify changes from state.
3. Offer a fresh observation after a confirmed action. The matched tasks still took 18 more calls on the SDK. Keep the new snapshot and existing window checks while reducing separate observation calls. Preserve scoped native text replacement, which kept the exact contact value in this test.

## Semantic controls A/B on Android 17

The baseline is the handoff commit `442ddf0`. The candidate adds native `android_scroll` and `android_set_progress`, plus `checked`, `selected`, `focused`, `enabled`, `scrollDirections`, and `range` fields on relevant nodes. Native progress uses the observed control's units and finite bounds. Native scroll uses declared directions and accepts a fractional page amount on Android 15 and later when the control supports it. The next observation verifies the action. [Android accessibility actions](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction) · [Android range metadata](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.RangeInfo)

DeepSeek Flash used the same runner, task text and per-case setup from the first comparison for both variants. Both variants used the same test-only bridge to the APK's MCP server and authorized the expected destination through its consent UI when requested. The permitted extra packages were Settings Intelligence for the battery task and System UI for brightness. Each of the five original tasks ran once per variant and was scored by independent state. Each variant passed 4 of 5 tasks and used 106 calls in total.

| AndroidLab task | Baseline | Semantic controls | Readback |
| --- | --- | --- | --- |
| `setting_5` Battery percentage | Pass, 14 calls | Pass, 10 calls | Both enabled the setting. The candidate read the switch's `checked` state. This run used Settings search rather than the new scroll action. |
| `setting_8` Brightness 0% | Pass, 18 calls | Pass, 12 calls | Both reached the display minimum. The candidate saw the native `0…65535` range and set `0` with one progress action. |
| `clock_6` Weekend 22:30 alarm | Fail, 22 calls | Fail, 20 calls | Both saved 22:00, with the correct label and weekend days. The agent confirmed the time picker without selecting 30 minutes. |
| `clock_2` 06:45, Argon, vibration off | Pass, 30 calls | Pass, 38 calls | Both databases held the correct time, sound and vibration state. |
| `clock_15` London and Barcelona | Pass, 22 calls | Pass, 26 calls | Both cities appeared in the final world clock and in the saved preferences. |

In a supplemental pair, both versions enabled the battery percentage by navigating the Settings list instead of Search. The baseline used two coordinate swipes and 10 calls. The candidate used one native forward scroll and 9 calls. The five-task totals above use the original instructions.

The native controls fixture independently verified range metadata, the resulting value after `android_set_progress`, rejection of out-of-range input, checked and selected state, native list scrolling, snapshot consumption and revocation. Raw tool traces, state readbacks, and the bridge patch are in `build/reports/android-use-semantic/`.

## Post-action snapshot A/B on Android 17

The control is the semantic-controls commit `a91cf79`. The candidate returns a fresh tree-only `observation` after a successful action, including its `snapshotId`. It briefly waits for UI updates, then reads within the existing grant. If the new state cannot be read, the reply keeps the action status and supplies an `observationError`. An explicit observation can request handoff. The attached snapshot uses the same protection, revocation and stale-input checks as `android_observe`.

Both versions used the same five original AndroidLab tasks, fixture preparation, permission decisions and DeepSeek Flash driver. Both received the same instruction to reuse an attached observation, verify every requested final value and correct any discrepancy. Each case ran once per version. The standalone APK also supplies the completion instruction in its Android Use turn prompt.

| AndroidLab task | Semantic controls | Post-action snapshots |
| --- | ---: | ---: |
| `setting_5` Battery percentage | Pass, 9 calls | Pass, 7 calls |
| `setting_8` Brightness 0% | Pass, 12 calls | Pass, 7 calls |
| `clock_6` Weekend 22:30 alarm | Pass, 24 calls | Pass, 16 calls |
| `clock_2` 06:45, Argon, vibration off | Pass, 40 calls | Pass, 29 calls |
| `clock_15` London and Barcelona | Pass, 26 calls | Pass, 18 calls |
| Total | 5/5, 111 calls | 5/5, 77 calls |

Explicit observations fell from 57 to 21. The weekend alarm had the correct 22:30 time, weekend repeat mask, label and enabled flag in both databases. Under the stronger verification instruction the control also corrected the minute-selection failure from the previous evaluation. The matched run measures the call savings from returning a new snapshot.

The fixture chained range, checkbox, selection and scroll actions using only the returned snapshot IDs. It verified that Back could be accepted while the resulting unauthorized window was withheld. Raw traces and independent readbacks are in `build/reports/android-use-post-action/`.
