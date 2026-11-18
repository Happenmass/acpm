#!/usr/bin/env python3
"""Compile three decoder graphs and both audio contexts, then lock a standalone SDK model."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", required=True, type=Path, help="Output of prepare.sh")
    parser.add_argument("--sdk", required=True, type=Path, help="Installed QAIRT SDK root")
    parser.add_argument("--output", required=True, type=Path, help="New per-target output directory")
    parser.add_argument("--profile", choices=["o2", "sm8850-o3"], required=True)
    parser.add_argument("--soc-id", type=int, help="Required for SM8850 O3; O2 targets only the DSP architecture")
    parser.add_argument("--dsp-arch", required=True, help="For example v81")
    parser.add_argument("--android-soc", required=True, help="Exact Android Build.SOC_MODEL")
    parser.add_argument("--notices", type=Path, required=True, help="Directory with model LICENSE and NOTICE")
    args = parser.parse_args()
    work, sdk, out = (p.resolve() for p in (args.work, args.sdk, args.output))
    if args.profile == "sm8850-o3" and (args.soc_id,args.dsp_arch,args.android_soc.upper()) != (88,"v81","SM8850"):
        parser.error("sm8850-o3 requires SM8850 / SoC 88 / V81")
    if not args.dsp_arch.startswith("v") or not args.dsp_arch[1:].isdigit():
        parser.error("Expected DSP architecture such as v81")
    target = {"dsp_arch":args.dsp_arch}
    if args.profile == "sm8850-o3":
        target["soc_model"] = args.soc_id
    out.mkdir(parents=True, exist_ok=True)
    model = out / "model"
    model.mkdir(exist_ok=True)
    host = sdk / "lib/x86_64-linux-clang"
    names = ["audio_cnn","audio_window","gqa","gqa64","gqa512"]
    converter = shutil.which("qairt-converter")
    generator = shutil.which("qnn-context-binary-generator")
    if not converter or not generator:
        parser.error("Activate the QAIRT environment and add its host tools to PATH")

    for name in names:
        subprocess.run([converter, "--input_network", work/"onnx"/f"{name}.onnx",
                        "--output_path", out/f"{name}.dlc", "--float_bitwidth", "16",
                        "--quantization_overrides", work/"dlc"/f"{name}.json"], check=True)

    groups = {"audio_cnn":["audio_cnn"], "audio_window":["audio_window"], "decoder":names[2:]}
    for context, graphs in groups.items():
        levels = {2:[],3:[]}
        for g in graphs:
            levels[3 if args.profile=="sm8850-o3" and g in ("gqa","gqa512") else 2].append(g)
        settings = out/f"{context}-htp.json"
        write_json(settings, {"devices":[target],
                              "graphs":[{"graph_names":gs,"O":level} for level,gs in levels.items() if gs],
                              "context":{"weight_sharing_enabled":context=="decoder"}})
        extension = out/f"{context}-extensions.json"
        write_json(extension, {"backend_extensions":{"shared_library_path":str(host/"libQnnHtpNetRunExtensions.so"),
                                                      "config_file_path":str(settings)}})
        subprocess.run([generator,"--model",host/"libQnnModelDlc.so","--backend",host/"libQnnHtp.so",
                        "--dlc_path",",".join(str(out/f"{g}.dlc") for g in graphs),
                        "--config_file",extension,"--binary_file",context,"--output_dir",model],check=True)

    template = json.loads((Path(__file__).resolve().parents[1]/"models.json").read_text())
    for name in template["models"]:
        if name not in {"audio_cnn.bin","audio_window.bin"}:
            shutil.copyfile(work/"assets"/name,model/name)
    for name in template["notices"]:
        shutil.copyfile(args.notices/name,model/name)
    def entry(name):
        path = model/name
        return [path.stat().st_size,digest(path)]
    write_json(out/"models.json", {"profiles":{args.profile:{
        "modelDirectory":str(model),"decoder":entry("decoder.bin"),"socModels":[args.android_soc],
        **({"htpArch":int(args.dsp_arch[1:])} if args.profile=="o2" else {})}},
        "models":{n:entry(n) for n in template["models"]},"notices":{n:digest(model/n) for n in template["notices"]}})

    arch = args.dsp_arch.upper()
    android = "lib/aarch64-android/"
    files = {n:android+n for n in ["libQnnHtp.so","libQnnSystem.so",f"libQnnHtp{arch}Stub.so"]}
    skel = f"libQnnHtp{arch}Skel.so"
    files[skel]=f"lib/hexagon-{args.dsp_arch}/unsigned/{skel}"
    runtime = {n:{"sdkPath":path,"sha256":digest(sdk/path)} for n,path in files.items()}
    notices = {n:digest(sdk/n) for n in ("LICENSE.pdf","NOTICE.txt","QNN_NOTICE.txt")}
    write_json(out/"libraries.json", {"libraries":runtime, "notices":notices})


if __name__ == "__main__":
    main()
