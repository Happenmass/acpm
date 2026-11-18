"""Make symmetric INT8 encodings for constant projection and convolution weights."""
import argparse
import json
from pathlib import Path
import numpy as np
import onnx
from onnx import numpy_helper

def write_encodings(source: Path, destination: Path) -> None:
    graph = onnx.load(source).graph
    # HTP v81 rejects weight-only INT8 on the three 3x3 CNN convolutions.
    # Quantize the CNN projection and retain its convolutions at FP16.
    axes = {"MatMul": 1}
    if source.stem != "audio_cnn":
        axes["Conv"] = 0
    weights = {node.input[1]: axes[node.op_type] for node in graph.node if node.op_type in axes}
    params = {}
    count = 0
    for tensor in graph.initializer:
        if tensor.name not in weights:
            continue
        array = numpy_helper.to_array(tensor)
        axis = weights[tensor.name]
        if not np.issubdtype(array.dtype, np.floating) or array.ndim <= axis:
            raise ValueError(f"Unexpected projection weight {tensor.name}: {array.shape} {array.dtype}")
        rows = np.moveaxis(array, axis, 0).reshape(array.shape[axis], -1)
        scales = np.maximum(np.max(np.abs(rows), axis=1) / 127, np.finfo(np.float32).tiny)
        params[tensor.name] = [
            {"bitwidth": 8, "is_symmetric": "True", "scale": float(scale), "offset": -128,
             "min": float(-128 * scale), "max": float(127 * scale)}
            for scale in scales
        ]
        count += array.size
    if not params:
        raise ValueError(f"No projection weights in {source}")
    destination.write_text(json.dumps({"activation_encodings": {}, "param_encodings": params}))
    print(f"{source.stem}: {len(params)} weight tensors, {count} elements", flush=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("onnx", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    write_encodings(args.onnx, args.output)
