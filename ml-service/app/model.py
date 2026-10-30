"""Loads a trained model and turns a request into a score on the scale the Java policy expects."""
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Optional

import joblib
import numpy as np

from app.features import FEATURE_NAMES, FEATURE_SCHEMA_VERSION, to_vector

# The Java decision policy's thresholds (application.yml: fraudguard.decision). Keep these equal to
# ml-review-at / ml-very-high-at there; override with environment variables if you change them.
POLICY_REVIEW_AT = float(os.environ.get("POLICY_ML_REVIEW_AT", "0.66"))
POLICY_VERY_HIGH_AT = float(os.environ.get("POLICY_ML_VERY_HIGH_AT", "0.70"))
POLICY_FLOOR = 0.35      # where a score of 0 lands: about where ordinary Isolation Forest rows sit


@dataclass(frozen=True)
class ModelBundle:
    model: object                          # IsolationForest or GradientBoostingClassifier
    model_version: str
    feature_schema_version: str
    feature_names: list
    metadata: dict
    model_type: str = "isolation_forest"   # or "gradient_boosting" (written by training/retrain.py)
    calibration: Optional[dict] = None     # {"review_raw": r, "very_high_raw": v}; None = already on policy scale


def load_bundle(path: Path) -> ModelBundle:
    """Loads a model file written by training/train.py or training/retrain.py.

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

    calibration = raw.get("calibration")
    if calibration is not None:
        r, v = calibration["review_raw"], calibration["very_high_raw"]
        if not (0.0 < r < v < 1.0):          # np.interp needs strictly increasing anchors
            raise ValueError(f"Invalid calibration anchors: review {r}, very_high {v}")

    return ModelBundle(
        model=raw["model"],
        model_version=raw["model_version"],
        feature_schema_version=raw["feature_schema_version"],
        feature_names=raw["feature_names"],
        metadata=raw.get("metadata", {}),
        model_type=raw.get("model_type", "isolation_forest"),
        calibration=calibration,
    )


def raw_score(bundle: ModelBundle, row: Mapping) -> float:
    """The model's own score. Higher = more suspicious, but the scale depends on the model type."""
    x = np.asarray([to_vector(row)])
    if bundle.model_type == "gradient_boosting":
        return float(bundle.model.predict_proba(x)[0, 1])        # probability of fraud
    # Isolation Forest: scikit-learn's score_samples is the NEGATIVE of the paper's anomaly score,
    # so flip the sign to make "higher = more unusual".
    return float(-bundle.model.score_samples(x)[0])


def to_policy_scale(raw: float, calibration: dict) -> float:
    """Monotone piecewise-linear map, so the ORDER of scores never changes:
         0 -> 0.35,   model's review cutoff -> 0.66,   model's "very high" cutoff -> 0.70,   1 -> 1.0
    """
    xs = [0.0, calibration["review_raw"], calibration["very_high_raw"], 1.0]
    ys = [POLICY_FLOOR, POLICY_REVIEW_AT, POLICY_VERY_HIGH_AT, 1.0]
    return float(np.interp(raw, xs, ys))


def anomaly_score(bundle: ModelBundle, row: Mapping) -> float:
    """The score sent to the Java service: on the scale its policy thresholds were written for.
    A ranking score, NOT a fraud probability."""
    raw = raw_score(bundle, row)
    return raw if bundle.calibration is None else to_policy_scale(raw, bundle.calibration)