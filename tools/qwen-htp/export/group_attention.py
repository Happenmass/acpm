"""Fold the two query heads in each GQA group into adjacent MatMul rows."""
import argparse
import onnx
from onnx import helper, TensorProto

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source")
parser.add_argument("output")
parser.add_argument("sequence", type=int, choices=(1, 64))
args = parser.parse_args()
model = onnx.load(args.source, load_external_data=False)
graph = model.graph
producers = {out: node for node in graph.node for out in node.output}
replace = {}
seq = args.sequence
name = "gqa" if seq == 1 else "gqa64"
for i, softmax in enumerate(n for n in graph.node if n.op_type == "Softmax"):
    scale = producers[producers[softmax.input[0]].input[0]]
    qk = producers[scale.input[0]]
    pv, = (n for n in graph.node if n.op_type == "MatMul" and n.input[0] == softmax.output[0])
    key = f"new_key_{i}"
    value = f"new_value_{i}"
    def joined(delta):
        concat, = (n for n in graph.node if n.op_type == "Concat" and list(n.input) == [delta.replace("new_", "past_"), delta])
        return concat.output[0]

    prefix = f"{name}_{i}_"
    def shape(src, out, dims, suffix):
        tensor = prefix + suffix + "_shape"
        graph.initializer.append(helper.make_tensor(tensor, TensorProto.INT64, [4], dims))
        return helper.make_node("Reshape", [src, tensor], [out], name=prefix+suffix)

    q, kt, scores = (prefix+s for s in ("grouped_q", "transposed_k", "grouped_scores"))
    probs, context = (prefix+s for s in ("grouped_probs", "grouped_context"))
    dots = [
        shape(qk.input[0], q, [1, 8, 2*seq, 128], "group_q"),
        helper.make_node("Transpose", [joined(key)], [kt], name=prefix+"transpose_k", perm=[0,1,3,2]),
        helper.make_node("MatMul", [q, kt], [scores if seq>1 else qk.output[0]], name=prefix+"qk"),
    ]
    values = []
    if seq>1:
        dots.append(shape(scores,qk.output[0],[1,16,seq,1024+seq],"restore_mask_shape"))
        values.append(shape(pv.input[0],probs,[1,8,2*seq,1024+seq],"group_probs"))
    values.extend([
        helper.make_node("MatMul", [probs if seq>1 else pv.input[0],joined(value)], [context], name=prefix+"pv"),
        shape(context,pv.output[0],[1,16,seq,128],"restore_context_shape"),
    ])
    replace[qk.name],replace[pv.name] = dots,values
if len(replace)!=56:
    raise ValueError("Expected 28 QK and PV pairs")
needed={v.name for v in graph.output}
kept=[]
for n in reversed([item for n in graph.node for item in replace.get(n.name,[n])]):
    if needed.intersection(n.output):
        needed.update(n.input)
        kept.append(n)
weights=[t for t in graph.initializer if t.name in needed]
graph.ClearField("node")
graph.node.extend(reversed(kept))
graph.ClearField("initializer")
graph.initializer.extend(weights)
graph.name=name
onnx.save(model,args.output)
