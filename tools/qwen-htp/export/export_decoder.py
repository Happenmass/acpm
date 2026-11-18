"""Export decoder prefill/decode blocks and a tied output projection."""
from release_paths import SOURCE_DIR, WORK_DIR, MODEL_DIR
import argparse
import gc
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from transformers import AutoModelForMultimodalLM
from decoder_graph import DecoderBlock, causal_mask


class OutputHead(torch.nn.Module):
    def __init__(self, norm, head):
        super().__init__()
        self.norm = norm
        self.head = head

    def forward(self, hidden):
        hidden = self.norm(hidden)
        # Smaller constant MatMul widths avoid a single very wide HTP FC node.
        return torch.cat([torch.nn.functional.linear(hidden, part)
                          for part in self.head.weight.split(8192)], dim=-1)


def export(module, inputs, path, names, output_names, report):
    with torch.inference_mode():
        expected = [tensor.numpy() for tensor in module(*inputs)] if isinstance(module, DecoderBlock) else [module(*inputs).numpy()]
        torch.onnx.export(module, inputs, str(path), input_names=names,
                          output_names=output_names, opset_version=17, dynamo=False)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    session = ort.InferenceSession(str(path), sess_options=options, providers=['CPUExecutionProvider'])
    actual = session.run(None, {name: tensor.numpy() for name, tensor in zip(names, inputs)})
    errors = []
    for got, want in zip(actual, expected):
        # The original AR64 block alignment uses a wider absolute FP32 bound
        # for the batched CPU reductions. Keep the original AR1/head threshold.
        atol = 5e-3 if inputs[0].shape[1] == 64 else 4e-4
        np.testing.assert_allclose(got, want, atol=atol, rtol=4e-3)
        errors.append(float(np.abs(got-want).max()))
    report[path.stem] = {'max_abs_error': max(errors), 'output_count': len(actual), 'onnx_bytes': path.stat().st_size}
    del session, actual, expected
    gc.collect()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--capacity', type=int, default=1024)
    parser.add_argument('--prefill', type=int, default=64)
    parser.add_argument('--output', type=Path, default=Path(str(WORK_DIR / 'artifacts/decoder')))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(4)
    torch.manual_seed(11)
    model = AutoModelForMultimodalLM.from_pretrained(str(MODEL_DIR), local_files_only=True,
                                                  dtype=torch.float32, attn_implementation='eager').eval()
    language = model.model.language_model
    language.embed_tokens.weight.detach().half().numpy().tofile(args.output/'embedding.fp16')
    report = {'cache_capacity': args.capacity, 'prefill_tokens': args.prefill, 'layers_per_block': 4}
    for offset in range(0, 28, 4):
        block = DecoderBlock(list(language.layers[offset:offset+4])).eval()
        for length in [1, args.prefill]:
            name = f'decoder_{offset//4}_s{length}'
            hidden = torch.randn(1, length, 1024)
            positions = torch.arange(4, 4+length)[None, :]
            with torch.inference_mode():
                cos, sin = language.rotary_emb(hidden, positions)
            cache = [torch.zeros(1, 8, args.capacity, 128) for _ in range(8)]
            for tensor in cache:
                tensor[:, :, :4] = torch.randn(1, 8, 4, 128)
            inputs = (hidden, cos[:, None], sin[:, None], causal_mask(4, length, args.capacity), *cache)
            names = ['hidden', 'cos', 'sin', 'attention_mask'] + [f'past_{kind}_{i}' for i in range(4) for kind in ['key', 'value']]
            outputs = ['hidden_out'] + [f'new_{kind}_{i}' for i in range(4) for kind in ['key', 'value']]
            export(block, inputs, args.output/f'{name}.onnx', names, outputs, report)
            (args.output/'alignment.json').write_text(json.dumps(report, indent=2))
            print(name, report[name], flush=True)
    head = OutputHead(language.norm, model.lm_head).eval()
    export(head, (torch.randn(1, 1, 1024),), args.output/'output_head.onnx', ['hidden'], ['logits'], report)
    (args.output/'alignment.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2), flush=True)


if __name__ == '__main__':
    main()
