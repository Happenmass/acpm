import os
from pathlib import Path
SOURCE_DIR = Path(os.environ.get("QWEN_SOURCE_DIR", Path(__file__).resolve().parents[1]))
WORK_DIR = Path(os.environ.get("QWEN_WORK_DIR", Path.home() / "qwen3-asr-qnn"))
MODEL_DIR = Path(os.environ.get("QWEN_MODEL_DIR", WORK_DIR / "checkpoint"))
