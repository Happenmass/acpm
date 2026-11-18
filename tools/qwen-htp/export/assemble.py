"""Assemble static 28-layer decoder graphs with the tied head and greedy selection."""
import argparse
import json
from pathlib import Path
import onnx
from onnx import TensorProto, helper

def assemble(work, sequence):
    name = f"decoder_s{sequence}"
    output = work / "onnx" / f"{name}.onnx"
    nodes, initializers, inputs, outputs = [], [], [], []
    encodings = {}
    previous_hidden = "hidden"
    external_name = output.name + ".weights"
    external_path = output.with_name(external_name)
    with external_path.open("wb") as weights:
        for block in range(8):
            is_head = block == 7
            if is_head:
                source = work / "onnx/output_head-folded.onnx"
                old_encodings = work / "dlc/output_head.json"
            else:
                old_stem = f"decoder_{block}_s1"
                source = work / "onnx" / f"{old_stem}.onnx" if sequence == 1 else work / "onnx" / f"decoder_{block}_s{sequence}.onnx"
                old_encodings = work / "dlc" / f"{old_stem}.json"
            graph = onnx.load(source).graph
            params = json.loads(old_encodings.read_text())["param_encodings"]
            actual_weights = {n.input[1] for n in graph.node if n.op_type == "MatMul" and len(n.input) > 1}
            assert set(params) <= actual_weights, (source, set(params) - actual_weights)
            scope = f"b{block}_"
            io_names = {"hidden": previous_hidden}
            if is_head and sequence != 1:
                # The caller marks the last real row. Project only this row so padding
                # in the final prefill batch doesn't affect token choice.
                inputs.append(helper.make_tensor_value_info("last_row", TensorProto.FLOAT, [1, 1, sequence]))
                nodes.append(helper.make_node("MatMul", ["last_row", previous_hidden], ["selected_hidden"], name="select_last_row"))
                io_names["hidden"] = "selected_hidden"
            if not is_head:
                io_names.update({key: key for key in ("cos", "sin", "attention_mask")})
                for layer in range(4):
                    for kind in ("key", "value"):
                        for age in ("past", "new"):
                            io_names[f"{age}_{kind}_{layer}"] = f"{age}_{kind}_{4 * block + layer}"
                io_names["hidden_out"] = f"hidden_after_{block}"
                for item in graph.input:
                    if block == 0 or item.name.startswith("past_"):
                        item.name = io_names[item.name]
                        inputs.append(item)
                for item in graph.output:
                    if item.name.startswith("new_"):
                        item.name = io_names[item.name]
                        outputs.append(item)
                previous_hidden = io_names["hidden_out"]
            else:
                io_names["logits"] = "logits"
            def scoped(name):
                return io_names.get(name, scope + name) if name else ""
            tensors = {tensor.name: tensor for tensor in graph.initializer}
            for key, encoding in params.items():
                tensor = tensors[key]
                assert tensor.dims[1] == len(encoding), key
                encodings[scoped(key)] = encoding
            for tensor in graph.initializer:
                tensor.name = scoped(tensor.name)
                if len(tensor.raw_data) >= 4096:
                    offset = weights.tell()
                    weights.write(tensor.raw_data)
                    onnx.external_data_helper.set_external_data(tensor, external_name, offset, len(tensor.raw_data))
                    tensor.ClearField("raw_data")
                initializers.append(tensor)
            for node in graph.node:
                node.name = scope + node.name
                node.input[:] = [scoped(value) for value in node.input]
                node.output[:] = [scoped(value) for value in node.output]
                nodes.append(node)
    nodes.append(helper.make_node("ArgMax", ["logits"], ["next_id_i64"], name="greedy_token", axis=2, keepdims=1))
    nodes.append(helper.make_node("Cast", ["next_id_i64"], ["next_id"], name="token_i32", to=TensorProto.INT32))
    outputs.insert(0, helper.make_tensor_value_info("next_id", TensorProto.INT32, [1, 1, 1]))
    model = helper.make_model(helper.make_graph(nodes, name, inputs, outputs, initializers),
                              ir_version=9, opset_imports=[helper.make_opsetid("", 17)])
    onnx.save(model, output)
    onnx.checker.check_model(str(output))
    (work / "dlc" / f"{name}.enc.json").write_text(json.dumps({"activation_encodings": {}, "param_encodings": encodings}))
    print(name, len(nodes), len(encodings), external_path.stat().st_size, flush=True)

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("work", type=Path)
parser.add_argument("sequence", type=int, choices=(1, 64))
args = parser.parse_args()
assemble(args.work, args.sequence)
