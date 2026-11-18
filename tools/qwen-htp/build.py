#!/usr/bin/env python3
"""Build the 24-second Qwen worker against the pinned QAIRT SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import shutil
import zipfile
from model_lock import load_model_lock

source = Path(__file__).resolve().parent
cache = Path.home() / ".cache/codex"
sdk = Path(os.environ.get("QAIRT_SDK_ROOT", cache / "dependencies/qairt/2.50.0/sdk/qairt/2.50.0.260828"))


def build(jni, assets, model_lock, profile, runtime_lock):
    lock = json.loads(runtime_lock.read_text())
    runtime = lock["libraries"]
    qwen = load_model_lock(model_lock, profile)
    models = qwen["models"]
    android = Path(os.environ.get("ANDROID_HOME") or Path.home() / "Library/Android/sdk")
    compilers = android / "ndk/28.2.13676358/toolchains/llvm/prebuilt"
    compiler = next(compilers.glob("*/bin/aarch64-linux-android26-clang++"))
    jni = jni / "arm64-v8a"
    jni.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        compiler, "-O3", "-std=c++20", "-Wall", "-Wextra", "-static-libstdc++", "-s",
        "-I", sdk / "include/QNN", source / "main.cc", "-ldl",
        "-o", jni / "libandroid_ai_qwen_htp.so",
    ], check=True)

    libraries = {}
    with zipfile.ZipFile(assets / "qwen-htp-runtime.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for name, item in sorted(runtime.items()):
            path = sdk / item["sdkPath"] if "sdkPath" in item else cache / item["cachePath"]
            with path.open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != item["sha256"]:
                    raise SystemExit(f"Unexpected QAIRT library checksum: {name}")
            archive.write(path, name)
            libraries[name] = path.stat().st_size

    notices = assets / "licenses/qualcomm-qairt-2.50"
    notices.mkdir(parents=True, exist_ok=True)
    for name, expected in lock["notices"].items():
        path = sdk / name
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise SystemExit(f"Unexpected QAIRT notice checksum: {name}")
        shutil.copyfile(path, notices / name)
    manifest = {"socModels": qwen["socModels"], "profile": profile, "models": {name: size for name, (size, _) in models.items()}, "libraries": libraries}
    if "htpArch" in qwen:
        manifest["htpArch"] = qwen["htpArch"]
    (assets / "qwen-htp-manifest.json").write_text(json.dumps(manifest) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jni", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--model-lock", type=Path, default=source / "models.json")
    parser.add_argument("--runtime-lock", type=Path, default=source / "libraries.json")
    parser.add_argument("--profile", default="sm8850-o3")
    args = parser.parse_args()
    build(args.jni, args.assets, args.model_lock, args.profile, args.runtime_lock)
