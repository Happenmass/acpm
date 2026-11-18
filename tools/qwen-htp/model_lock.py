"""Resolve common Qwen weights and the selected decoder artifact."""
import json
from pathlib import Path


def load_model_lock(path: Path, profile: str) -> dict:
    lock = json.loads(path.read_text())
    try:
        variant = lock["profiles"][profile]
    except KeyError as error:
        raise ValueError(f"Unknown model profile {profile!r} in {path}") from error
    return {
        **variant,
        "models": {**lock["models"], **variant.get("modelOverrides", {}), "decoder.bin": variant["decoder"]},
        "notices": lock["notices"],
    }
