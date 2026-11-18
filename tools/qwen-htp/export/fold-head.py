"""Expose output-head projections as initializers so QAIRT can quantize them."""
import argparse
from pathlib import Path
import onnx
from onnx import helper, numpy_helper

def fold_head(path: Path, output: Path) -> None:
    model = onnx.load(path)
    graph = model.graph
    initializers = {tensor.name: tensor for tensor in graph.initializer}
    producers = {name: node for node in graph.node for name in node.output}
    split = next(node for node in graph.node if node.op_type == "Split")
    sizes_node = producers[split.input[1]]
    sizes = numpy_helper.to_array(helper.get_node_attr_value(sizes_node, "value"))
    head = numpy_helper.to_array(initializers[split.input[0]])
    graph.initializer.remove(initializers[split.input[0]])
    graph.node.remove(sizes_node)
    graph.node.remove(split)
    start = 0
    for name, size in zip(split.output, sizes, strict=True):
        projection = next(node for node in graph.node if name in node.input)
        assert projection.op_type == "Transpose"
        weight = head[start:start + size].T.copy()
        graph.initializer.append(numpy_helper.from_array(weight, name=projection.output[0]))
        graph.node.remove(projection)
        start += size
    assert start == head.shape[0]
    onnx.checker.check_model(model)
    onnx.save(model, output)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    fold_head(args.input, args.output)
