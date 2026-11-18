"""Change the historical K ABI in an exported AR64 or AR1 GQA graph.
Keep the source external weights at the same relative path beside the output.
"""
import argparse
from pathlib import Path
import onnx
from onnx import helper

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("source", type=Path, help="Original unmodified GQA ONNX")
p.add_argument("target", type=Path, help="Output ONNX with the same external weights")
p.add_argument("--split-scores", action="store_true")
args = p.parse_args()
if args.source.resolve() == args.target.resolve():
    p.error("Choose a separate output file for the candidate graph")
model = onnx.load(args.source, load_external_data=False)
g = model.graph
ios = {v.name: v for v in [*g.input, *g.output]}
replacements = {}
discard_info = set()
for i in range(28):
    past, delta = f"past_key_{i}", f"new_key_{i}"
    past_t, delta_t = f"past_key_t_{i}", f"new_key_t_{i}"
    concat, = (n for n in g.node if past in n.input)
    trans, = (n for n in g.node if concat.output[0] in n.input)
    dot, = (n for n in g.node if trans.output[0] in n.input)
    assert concat.op_type == "Concat" and list(concat.input) == [past, delta]
    assert helper.get_attribute_value(concat.attribute[0]) == 2
    assert trans.op_type == "Transpose" and list(trans.attribute[0].ints) == [0, 1, 3, 2]
    assert dot.op_type == "MatMul" and dot.input[1] == trans.output[0]
    for name, target in ((past, past_t), (delta, delta_t)):
        io = ios[name]
        dims = io.type.tensor_type.shape.dim
        assert dims[1].dim_value == 8 and dims[3].dim_value == 128
        dims[2].dim_value, dims[3].dim_value = dims[3].dim_value, dims[2].dim_value
        io.name = target
    replacements[concat.name] = [
        helper.make_node("Transpose", [delta], [delta_t], name=trans.name, perm=[0, 1, 3, 2])
    ]
    discard_info.update([past, delta_t, concat.output[0], trans.output[0]])
    if args.split_scores:
        replacements[trans.name] = []
        a, b = f"score_past_{i}", f"score_delta_{i}"
        replacements[dot.name] = [
            helper.make_node("MatMul", [dot.input[0], past_t], [a], name=a),
            helper.make_node("MatMul", [dot.input[0], delta_t], [b], name=b),
            helper.make_node("Concat", [a, b], dot.output, name=dot.name, axis=3),
        ]
    else:
        replacements[trans.name] = [
            helper.make_node("Concat", [past_t, delta_t], trans.output, name=concat.name, axis=3)
        ]
nodes = [n for source in g.node for n in replacements.get(source.name, [source])]
info = [v for v in g.value_info if v.name not in discard_info]
g.ClearField("node")
g.node.extend(nodes)
g.ClearField("value_info")
g.value_info.extend(info)
onnx.save(model, args.target)
