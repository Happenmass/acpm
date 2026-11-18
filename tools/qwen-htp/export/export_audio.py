"""Export fixed-shape audio graphs with CPU packing between CNN and attention.

One CNN input holds 100 mel frames (one second). Transformer windows contain
at most 104 valid CNN tokens, matching n_window_infer=800. Padding keys are
masked; the caller discards padded outputs. This avoids data-dependent NonZero
and variable-length Split operations in the HTP graphs.
"""
from release_paths import SOURCE_DIR, WORK_DIR, MODEL_DIR
import argparse
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from safetensors import safe_open
from transformers import AutoConfig
from transformers.models.qwen3_asr.modeling_qwen3_asr import (
    Qwen3ASREncoder, Qwen3ASRMultiModalProjector,
)


class AudioCNN(torch.nn.Module):
    def __init__(self, encoder):
        super().__init__()
        self.encoder = encoder

    def forward(self, mel):
        e = self.encoder
        x = torch.nn.functional.gelu(e.conv2d1(mel.unsqueeze(1)))
        x = torch.nn.functional.gelu(e.conv2d2(x))
        x = torch.nn.functional.gelu(e.conv2d3(x))
        x = x.permute(0, 3, 1, 2).flatten(2)
        return e.conv_out(x) + e.positional_embedding.positional_embedding[:13]


class AudioWindow(torch.nn.Module):
    def __init__(self, encoder, projector):
        super().__init__()
        self.encoder = encoder
        self.projector = projector

    def forward(self, hidden, key_mask):
        e = self.encoder
        for layer in e.layers:
            residual = hidden
            x = layer.self_attn_layer_norm(hidden)
            attn = layer.self_attn
            shape = (1, hidden.shape[1], attn.num_heads, attn.head_dim)
            q = attn.q_proj(x).reshape(shape).transpose(1, 2)
            k = attn.k_proj(x).reshape(shape).transpose(1, 2)
            v = attn.v_proj(x).reshape(shape).transpose(1, 2)
            probs = torch.softmax(torch.matmul(q, k.transpose(-1, -2)) * attn.scaling + key_mask, dim=-1)
            x = torch.matmul(probs, v).transpose(1, 2).reshape(1, hidden.shape[1], -1)
            hidden = residual + attn.out_proj(x)
            x = layer.final_layer_norm(hidden)
            hidden = hidden + layer.fc2(layer.activation_fn(layer.fc1(x)))
        return self.projector(e.ln_post(hidden))


def load_audio(root):
    config = AutoConfig.from_pretrained(root, local_files_only=True)
    config.audio_config._attn_implementation = 'eager'
    encoder = Qwen3ASREncoder(config.audio_config).eval()
    projector = Qwen3ASRMultiModalProjector(config).eval()
    with safe_open(str(root / 'model.safetensors'), framework='pt', device='cpu') as weights:
        for module, prefix in [(encoder, 'model.audio_tower.'), (projector, 'model.multi_modal_projector.')]:
            state = {key[len(prefix):]: weights.get_tensor(key).float() for key in weights.keys() if key.startswith(prefix)}
            module.load_state_dict(state, strict=True)
    return encoder, projector


def export_and_compare(module, inputs, path, names, report):
    torch.onnx.export(module, inputs, str(path), input_names=names,
                      output_names=['audio_hidden'], opset_version=17, dynamo=False)
    session = ort.InferenceSession(str(path), providers=['CPUExecutionProvider'])
    expected = module(*inputs).detach().numpy()
    actual = session.run(None, {name: value.numpy() for name, value in zip(names, inputs)})[0]
    np.testing.assert_allclose(actual, expected, atol=3e-4, rtol=3e-3)
    report[path.stem] = {'shape': list(actual.shape), 'max_abs_error': float(np.max(np.abs(actual - expected)))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', type=Path, default=Path(str(MODEL_DIR)))
    parser.add_argument('--output', type=Path, default=Path(str(WORK_DIR / 'artifacts/audio')))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(4)
    torch.manual_seed(7)
    encoder, projector = load_audio(args.model)
    cnn = AudioCNN(encoder).eval()
    window = AudioWindow(encoder, projector).eval()
    report = {}
    with torch.inference_mode():
        # Validate packing and masked attention against the original HF encoder
        # for both complete and partial last chunks and across an 8-second window.
        for frames in [73, 100, 713, 800, 973, 2400]:
            padded = ((frames + 99) // 100) * 100
            mel = torch.randn(1, 128, padded)
            mask = (torch.arange(padded)[None, :] < frames).long()
            original = projector(encoder(mel, mask).last_hidden_state)
            chunks = mel.reshape(1, 128, -1, 100).permute(0, 2, 1, 3).reshape(-1, 128, 100)
            conv = cnn(chunks)
            lengths = encoder._post_cnn_length(mask.reshape(-1, 100).sum(-1))
            packed = torch.cat([chunk[:int(length)] for chunk, length in zip(conv, lengths)])
            results = []
            for segment in packed.split(104):
                n = segment.shape[0]
                x = torch.nn.functional.pad(segment, (0, 0, 0, 104-n)).unsqueeze(0)
                key_mask = torch.zeros(1, 1, 1, 104)
                key_mask[..., n:] = -10000.0
                results.append(window(x, key_mask)[0, :n])
            actual = torch.cat(results)
            torch.testing.assert_close(actual, original, atol=3e-4, rtol=3e-3)
            report[f'hf_frames_{frames}'] = {'tokens': len(actual), 'max_abs_error': float((actual-original).abs().max())}
        export_and_compare(cnn, (torch.randn(1, 128, 100),), args.output/'audio_cnn.onnx', ['mel'], report)
        export_and_compare(window, (torch.randn(1, 104, 896), torch.zeros(1, 1, 1, 104)),
                           args.output/'audio_window.onnx', ['hidden', 'key_mask'], report)
    (args.output/'alignment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
