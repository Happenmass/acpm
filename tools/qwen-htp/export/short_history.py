"""Build AR1 for up to 512 historical positions while retaining the full shared KV ABI."""
from pathlib import Path
import argparse
import onnx
from onnx import helper, TensorProto
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
source, output = args.source, args.output
if source.resolve() == output.resolve():
    parser.error("The full-history decoder must be retained as a separate graph.")
model = onnx.load(source, load_external_data=False)
graph = model.graph
prefix=[]
def vector(name, v):
    if not any(t.name==name for t in graph.initializer):
        graph.initializer.append(helper.make_tensor(name,TensorProto.INT64,[1],[v]))
    return name
def cut(name, axis, lo, hi, suffix):
    dest=name+'_'+suffix
    inputs=[name, vector('slice_const_'+str(lo),lo),vector('slice_const_'+str(hi),hi),vector('slice_const_'+str(axis),axis)]
    prefix.append(helper.make_node('Slice',inputs,[dest],name=dest))
    return dest
mask='attention_mask'
m0=cut(mask,3,0,512,'history512')
m1=cut(mask,3,1024,1025,'current')
prefix.append(helper.make_node('Concat',[m0,m1],['mask513'],name='mask513',axis=3))
rename={mask:'mask513'}
for i in range(28):
    for n,axis in [(f'past_key_t_{i}',3),(f'past_value_{i}',2)]:
        rename[n]=cut(n,axis,0,512,'visible512')
seen={k:0 for k in rename}
for node in graph.node:
    for j, x in enumerate(node.input):
        if x in rename:
            seen[x]+=1
            node.input[j]=rename[x]
if seen.pop(mask) != 28 or set(seen.values()) != {1}:
    raise ValueError("Expected 28 masks and one consumer per historical tensor")
nodes=[*prefix,*graph.node]
graph.ClearField('node')
graph.node.extend(nodes)
graph.name = "gqa512"
onnx.save(model,output)
