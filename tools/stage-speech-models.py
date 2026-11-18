#!/usr/bin/env python3
"""Stage the locked speech weights into the AI SDK APK assets."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import os
import sys

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS / "qwen-htp"))
from model_lock import load_model_lock

MODEL_CACHE = Path(os.environ.get("SPEECH_MODEL_CACHE", Path.home() / ".cache/codex/models"))


def verify(path, size, digest):
    if not path.is_file() or path.stat().st_size != size:
        raise SystemExit(f"Missing or invalid pinned weight: {path}")
    with path.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != digest:
            raise SystemExit(f"Pinned weight checksum mismatch: {path}")


def stage(output, model_lock, profile):
    qwen = load_model_lock(model_lock, profile)
    speakers = json.loads((TOOLS / "speech-models/speakers.json").read_text())
    qwen_directory = MODEL_CACHE / qwen["modelDirectory"]
    models = {
        "qwen": {name: (qwen_directory / name, size, sha) for name, (size, sha) in qwen["models"].items()},
        "speakers": {name: (MODEL_CACHE / path, size, sha) for name, (path, size, sha) in speakers.items()},
    }
    manifest = {}
    for group, entries in models.items():
        target = output / "speech-models" / group
        target.mkdir(parents=True, exist_ok=True)
        files = {}
        for name, (source, size, sha) in entries.items():
            verify(source, size, sha)
            shutil.copyfile(source, target / name)
            files[name] = [size, sha]
        version = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()[:16]
        manifest[group] = {"revision": version, "files": files}

    notices = output / "licenses/speech-models"
    notices.mkdir(parents=True, exist_ok=True)
    for name, sha in qwen["notices"].items():
        content = (qwen_directory / name).read_bytes()
        if hashlib.sha256(content).hexdigest() != sha:
            raise SystemExit(f"Qwen notice checksum mismatch: {name}")
        (notices / f"qwen3-asr-{name}").write_bytes(content)
    for name in ("3d-speaker-LICENSE", "pyannote-LICENSE", "NOTICE"):
        shutil.copyfile(TOOLS / "speech-models" / name, notices / name)
    (output / "speech-models/manifest.json").write_text(json.dumps(manifest) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model-lock", type=Path, default=TOOLS / "qwen-htp/models.json")
    parser.add_argument("--profile", default="sm8850-o3")
    args = parser.parse_args()
    stage(args.output, args.model_lock, args.profile)
