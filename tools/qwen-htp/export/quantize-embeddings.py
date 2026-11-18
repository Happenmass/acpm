"""Quantize a Qwen token embedding table using one float scale per token."""
import argparse
import json
from pathlib import Path
import numpy as np

def quantize(path: Path, output: Path) -> None:
    shape = (151936, 1024)
    if path.stat().st_size != np.prod(shape) * 2:
        raise ValueError(f"Unexpected embedding size: {path}")
    source = np.memmap(path, mode="r", dtype="<f2", shape=shape)
    weights = np.memmap(output / "embedding.int8", mode="w+", dtype="i1", shape=shape)
    scales = np.memmap(output / "embedding-scales.f32", mode="w+", dtype="<f4", shape=shape[:1])
    squared_error = squared_reference = max_error = 0.0
    for start in range(0, shape[0], 2048):
        rows = slice(start, start + 2048)
        values = source[rows].astype(np.float32)
        scale = np.maximum(np.max(np.abs(values), axis=1) / 127, np.finfo(np.float32).tiny)
        scaled = np.rint(values / scale[:, None]).clip(-127, 127).astype(np.int8)
        weights[rows] = scaled
        scales[rows] = scale
        error = scaled * scale[:, None] - values
        squared_error += float(np.sum(np.square(error), dtype=np.float64))
        squared_reference += float(np.sum(np.square(values), dtype=np.float64))
        max_error = max(max_error, float(np.max(np.abs(error))))
    weights.flush()
    scales.flush()
    print(json.dumps({
        "shape": shape,
        "max_abs_error": max_error,
        "relative_l2_error": (squared_error / squared_reference) ** 0.5,
    }, indent=2))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    quantize(args.input, args.output)
