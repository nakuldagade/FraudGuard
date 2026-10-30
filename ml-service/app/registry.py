"""Which model version is live? Reads models/registry.json, which training/retrain.py updates
when a challenger is promoted. Rolling back = set "active" back to the previous version."""
import json
import re
from pathlib import Path

MODELS_DIR = Path(__file__).resolve().parent.parent / "models"
REGISTRY_PATH = MODELS_DIR / "registry.json"
DEFAULT_VERSION = "isoforest-v1"          # used when the registry is missing or empty

# A version becomes part of a file name, so only allow plain characters (no "../" tricks)
_SAFE_VERSION = re.compile(r"^[A-Za-z0-9._-]+$")


def active_version() -> str:
    version = DEFAULT_VERSION
    if REGISTRY_PATH.exists():
        text = REGISTRY_PATH.read_text().strip()
        if text:
            try:
                version = json.loads(text).get("active", DEFAULT_VERSION)
            except json.JSONDecodeError as e:
                raise ValueError(f"{REGISTRY_PATH} is not valid JSON: {e}") from e
    if not _SAFE_VERSION.match(version):
        raise ValueError(f"Unsafe model version in registry: {version!r}")
    return version


def model_path(version: str) -> Path:
    return MODELS_DIR / f"{version}.joblib"