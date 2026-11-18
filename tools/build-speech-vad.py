"""Build the pyannote JNI adapter against the published Android ORT C API headers."""
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

VERSION = "1.24.3"
AAR = f"onnxruntime-android-{VERSION}.aar"
SHA256 = "67397e4a970e75617f765d2015ceaf911917e1d822276cfb5792744e8085cbce"
URL = f"https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/{VERSION}/{AAR}"
HEADERS = ("onnxruntime_c_api.h", "onnxruntime_ep_c_api.h")


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main():
    ndk, output = map(Path, sys.argv[1:])
    cache = Path.home() / ".cache/codex/dependencies/onnxruntime-android" / VERSION
    cache.mkdir(parents=True, exist_ok=True)
    download = cache / AAR
    if not download.is_file():
        with tempfile.NamedTemporaryFile(dir=cache) as tmp:
            urllib.request.urlretrieve(URL, tmp.name)
            if digest(Path(tmp.name)) != SHA256:
                raise SystemExit("Unexpected ONNX Runtime Android AAR checksum")
            download.write_bytes(Path(tmp.name).read_bytes())
    if digest(download) != SHA256:
        raise SystemExit("Unexpected cached ONNX Runtime Android AAR checksum")
    target = output / "arm64-v8a/libandroid_ai_vad.so"
    target.parent.mkdir(parents=True, exist_ok=True)
    compiler = next((ndk / "toolchains/llvm/prebuilt").glob("*/bin/aarch64-linux-android26-clang++"))
    source = Path(__file__).resolve().parents[1] / "speech/src/main/cpp/vad.cc"
    with tempfile.TemporaryDirectory() as include, zipfile.ZipFile(download) as archive:
        for name in HEADERS:
            (Path(include) / name).write_bytes(archive.read(f"headers/{name}"))
        subprocess.run([compiler, "-shared", "-fPIC", "-O2", "-std=c++17", "-static-libstdc++",
                        "-I", include, source, "-ldl", "-o", target], check=True)


if __name__ == "__main__":
    main()
