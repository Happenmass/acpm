# Export Qwen3-ASR-0.6B for Android HTP

The two SDK APKs use the same checkpoint, greedy vocabulary, 24-second frontend, W8 projections, FP16 activations, AR64 prefill and 1,024 positions of shared KV. Both include two AR1 graphs. The worker reads the first 512 history positions while the valid count is at most 512, then uses the full graph. Both write absolute KV positions into the same buffers.

| APK profile | Audio and AR64 | Both AR1 graphs | Device target |
|---|---|---|---|
| `o2` | O2 | O2 | Compiled for a Hexagon architecture, with no SoC model override. The bundled build targets V81. |
| `sm8850-o3` | O2 | O3 | SM8850 (SoC 88, V81), measured on HONOR Magic V6. |

An HTP context, matching Stub/Skel libraries, Android ABI and page size are part of the target. The shipped integration uses ARM64 and 4 KiB pages. The generic profile accepts the known SM8850 target or checks the architecture reported by QNN. Other Hexagon generations need their own O2 export and runtime. Validate every new combination on hardware.

## 1. Generate portable ONNX and weights

Use Python 3.12 in an export environment with the versions in `requirements.txt`. Obtain the official Apache-2.0 Qwen checkpoint and its notices. The work directory needs room for both external-weight files, intermediate ONNX, QAIRT DLCs and contexts (allow 30 GiB or more).

```sh
python3.12 -m venv .venv-export
. .venv-export/bin/activate
pip install -r tools/qwen-htp/export/requirements.txt
export QWEN_WORK_DIR="$HOME/.cache/qwen3-asr/export"
export QWEN_MODEL_DIR="$PWD/checkpoint"
hf download Qwen/Qwen3-ASR-0.6B-hf --revision 7f1569a48a89f3e3f4dc3a5c9d28bddd903bc76c --local-dir "$QWEN_MODEL_DIR"
bash tools/qwen-htp/export/prepare.sh
```

This step exports the audio CNN and transformer, AR1/AR64 decoder blocks and tied output head. It checks the original blocks against PyTorch, assembles 28 layers, folds the grouped query heads, transposes historical K, generates the 512-position graph, and writes per-channel quantization encodings and frontend assets. The AR1 input and output shapes are identical for both history lengths. Reuse this directory when compiling for a second device.

## 2. Compile for the target

Use a separate Qualcomm QAIRT 2.50.0.260828 environment on Linux x86-64. Obtain the SDK separately, install its converter dependencies, and source `bin/envsetup.sh` so `qairt-converter` and `qnn-context-binary-generator` are on PATH. The generator must have access to the matching x86-64 HTP backend extension. The Android runtime lock is produced from the Android libraries, DSP skeleton and notices in that SDK. Set `MODEL_NOTICES` to a directory containing the model license and attribution (`LICENSE` and `NOTICE`). Keep the notices with redistributed weights.

```sh
export MODEL_NOTICES="$PWD/tools/qwen-htp/export"
export QAIRT_SDK_ROOT=/opt/qairt/2.50.0.260828
. "$QAIRT_SDK_ROOT/bin/envsetup.sh"
python3 tools/qwen-htp/export/compile.py --work "$QWEN_WORK_DIR" --sdk "$QAIRT_SDK_ROOT" \
  --output "$PWD/v81-o2" --profile o2 --dsp-arch v81 --android-soc SM8850 --notices "$MODEL_NOTICES"
python3 tools/qwen-htp/export/compile.py --work "$QWEN_WORK_DIR" --sdk "$QAIRT_SDK_ROOT" \
  --output "$PWD/sm8850-o3" --profile sm8850-o3 --soc-id 88 --dsp-arch v81 --android-soc SM8850 --notices "$MODEL_NOTICES"
```

Each output has all three model contexts, weights, `models.json` and `libraries.json`. For another device choose `o2`, supply its Hexagon architecture and exact Android SoC name, then verify the public sample and the short/full transition. The output directory in the generated lock is absolute. Update `modelDirectory` if you move the weights to another machine.

## 3. Build the SDK APK

Configure Android SDK/NDK 28.2.13676358 and the other speech and agent dependencies documented in the project README and speech guide. The normal Gradle build verifies pinned model and runtime hashes. For your own export, select the profile and pass both generated lock files.

```sh
./gradlew :sample:assembleRelease -PspeechHtp -PagentBackends=pi,codex \
  -PhtpProfile=o2 -PhtpModelLock="$PWD/v81-o2/models.json" -PhtpRuntimeLock="$PWD/v81-o2/libraries.json"
```

To build both pinned distribution artifacts, run `bash tools/qwen-htp/build-release-pair.sh /path/to/output`. Each filename includes its profile. For a single pinned build, select either profile with `-PhtpProfile`; the existing default is `sm8850-o3`. The two SDK APKs share an Android package ID. For public distribution, sign each with the same certificate as the recorder Binder client. Downloaders choose one companion APK. The recorder APK contains no speech weights.

For exact output checks, the packaged native worker accepts an optional final `--token-ids` after its normal runtime, model directory and idle timeout arguments. It emits the complete greedy IDs on an extra output line. The service uses the ordinary protocol. The `htp-tokens` instrumentation stage in the isolated SDK test runs the same worker in the App UID for this check.
