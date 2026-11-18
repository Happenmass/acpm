#!/usr/bin/env bash
# Generate portable ONNX graphs, W8 encodings and frontend assets from the checkpoint.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
: "${QWEN_WORK_DIR:?Choose an empty export directory}"
: "${QWEN_MODEL_DIR:?Point to the Qwen3-ASR-0.6B-hf checkpoint}"
mkdir -p "$QWEN_WORK_DIR"
export QWEN_WORK_DIR=$(cd "$QWEN_WORK_DIR" && pwd)
export QWEN_MODEL_DIR=$(cd "$QWEN_MODEL_DIR" && pwd)
w="$QWEN_WORK_DIR"
mkdir -p "$w/onnx" "$w/dlc" "$w/assets"
python "$here/export_audio.py" --output "$w/onnx"
python "$here/export_decoder.py" --capacity 1024 --prefill 64 --output "$w/onnx"
python "$here/export_frontend.py"
cp "$w/artifacts/frontend/"{*.i32,*.f32,token_bytes.bin} "$w/assets/"
python "$here/quantize-embeddings.py" "$w/onnx/embedding.fp16" "$w/assets"
python "$here/fold-head.py" "$w/onnx/output_head.onnx" "$w/onnx/output_head-folded.onnx"
for name in audio_cnn audio_window decoder_{0..6}_s1 output_head; do
    input="$name"
    if [[ "$name" == output_head ]]; then input=output_head-folded; fi
    python "$here/weight-encodings.py" "$w/onnx/$input.onnx" "$w/dlc/$name.json"
done
for seq in 1 64; do
    name=gqa
    if [[ "$seq" == 64 ]]; then name=gqa64; fi
    python "$here/assemble.py" "$w" "$seq"
    python "$here/group_attention.py" "$w/onnx/decoder_s$seq.onnx" "$w/onnx/grouped-$seq.onnx" "$seq"
    python "$here/../transpose-history.py" "$w/onnx/grouped-$seq.onnx" "$w/onnx/$name.onnx"
    cp "$w/dlc/decoder_s$seq.enc.json" "$w/dlc/$name.json"
done
python "$here/short_history.py" "$w/onnx/gqa.onnx" "$w/onnx/gqa512.onnx"
cp "$w/dlc/gqa.json" "$w/dlc/gqa512.json"
