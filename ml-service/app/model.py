"""Loads the trained model and turns a request into an anomaly score."""
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

import joblib
import numpy as np
from sklearn.ensemble import IsolationForest

from app.features import FEATURE_NAMES, FEATURE_SCHEMA_VERSION, to_vector


@dataclass(frozen=True)
class ModelBundle:
    model: IsolationForest
    model_version: str
    feature_schema_version: str
    feature_names: list
    metadata: dict


def load_bundle(path: Path) -> ModelBundle:
    """Loads a model file written by training/train.py.

    Only load files you trust: joblib files are pickles, and unpickling can run code.
    """
    if not Path(path).exists():
        raise FileNotFoundError(
            f"Model file not found: {path}. Create it with: python training/train.py")
    raw = joblib.load(path)

    # Fail at startup (not on the first request) if the model and the code disagree about features
    if raw["feature_schema_version"] != FEATURE_SCHEMA_VERSION or raw["feature_names"] != FEATURE_NAMES:
        raise ValueError(
            f"Model was trained on feature schema {raw['feature_schema_version']} "
            f"but this service uses schema {FEATURE_SCHEMA_VERSION}. Retrain the model.")
    return ModelBundle(
        model=raw["model"],
        model_version=raw["model_version"],
        feature_schema_version=raw["feature_schema_version"],
        feature_names=raw["feature_names"],
        metadata=raw.get("metadata", {}),
    )


def anomaly_score(bundle: ModelBundle, row: Mapping) -> float:
    """Isolation Forest anomaly score: roughly 0.35-0.5 for ordinary rows, rising towards 1.0
    for rows that are easy to isolate (unusual). It is a ranking score, NOT a fraud probability."""
    x = np.asarray([to_vector(row)])
    # scikit-learn's score_samples is the NEGATIVE of the paper's anomaly score; flip the sign
    # so that "higher = more unusual".
    return float(-bundle.model.score_samples(x)[0])