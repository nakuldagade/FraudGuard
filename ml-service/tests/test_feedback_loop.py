"""Tests for the Phase 9 feedback loop.  Run from ml-service/:
    python -m pytest tests/test_feedback_loop.py -v
"""
import sys
from pathlib import Path

import joblib
import numpy as np
import pytest
from sklearn.ensemble import IsolationForest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))                  # app.*
sys.path.insert(0, str(ROOT / "training"))     # build_dataset, retrain, train

import build_dataset  # noqa: E402
import retrain  # noqa: E402
from app import registry as registry_module  # noqa: E402
from app.features import FEATURE_NAMES, FEATURE_SCHEMA_VERSION, to_vector  # noqa: E402
from app.model import (  # noqa: E402
    POLICY_FLOOR, POLICY_REVIEW_AT, POLICY_VERY_HIGH_AT,
    ModelBundle, anomaly_score, load_bundle, to_policy_scale,
)

ROW = {
    "amount": 500.0, "average_amount": 400.0, "history_count": 10, "amount_z_score": 0.5,
    "transaction_count_5m": 1, "transaction_count_1h": 2, "seconds_since_previous": 600,
    "new_device": False, "new_merchant": False, "country_changed": False, "hour_of_day": 14,
}
CAL = {"review_raw": 0.5, "very_high_raw": 0.8}


class FakeIsolationForest:
    def score_samples(self, x):
        return np.array([-0.55])      # scikit-learn convention: the NEGATIVE of the anomaly score


class FakeBooster:
    def predict_proba(self, x):
        return np.array([[0.1, 0.9]])


def make_bundle(model, **kwargs):
    return ModelBundle(model=model, model_version="fake", feature_schema_version=FEATURE_SCHEMA_VERSION,
                       feature_names=FEATURE_NAMES, metadata={}, **kwargs)


# ---------------------------------------------------------------- calibration (app/model.py)

def test_isolation_forest_score_passes_through_unchanged():
    assert anomaly_score(make_bundle(FakeIsolationForest()), ROW) == pytest.approx(0.55)


def test_calibrated_model_is_mapped_onto_the_policy_scale():
    bundle = make_bundle(FakeBooster(), model_type="gradient_boosting", calibration=CAL)
    # 0.9 is halfway between the "very high" anchor (0.8) and 1.0
    expected = POLICY_VERY_HIGH_AT + 0.5 * (1.0 - POLICY_VERY_HIGH_AT)
    assert anomaly_score(bundle, ROW) == pytest.approx(expected)


def test_anchors_land_exactly_on_the_policy_thresholds():
    assert to_policy_scale(0.0, CAL) == pytest.approx(POLICY_FLOOR)
    assert to_policy_scale(CAL["review_raw"], CAL) == pytest.approx(POLICY_REVIEW_AT)
    assert to_policy_scale(CAL["very_high_raw"], CAL) == pytest.approx(POLICY_VERY_HIGH_AT)
    assert to_policy_scale(1.0, CAL) == pytest.approx(1.0)


def test_mapping_never_changes_the_order_of_scores():
    mapped = [to_policy_scale(r, CAL) for r in np.linspace(0, 1, 201)]
    assert all(a <= b for a, b in zip(mapped, mapped[1:]))


def _write_model(path, calibration):
    joblib.dump({
        "model": IsolationForest(n_estimators=5),       # never called: load_bundle only validates
        "model_version": "test-model",
        "feature_schema_version": FEATURE_SCHEMA_VERSION,
        "feature_names": FEATURE_NAMES,
        "calibration": calibration,
    }, path)


def test_load_bundle_rejects_anchors_that_are_not_increasing(tmp_path):
    _write_model(tmp_path / "m.joblib", {"review_raw": 0.9, "very_high_raw": 0.5})
    with pytest.raises(ValueError, match="calibration"):
        load_bundle(tmp_path / "m.joblib")


def test_load_bundle_accepts_valid_anchors(tmp_path):
    _write_model(tmp_path / "m.joblib", CAL)
    assert load_bundle(tmp_path / "m.joblib").calibration == CAL


# ---------------------------------------------------------------- registry (app/registry.py)

def test_registry_returns_the_active_version(tmp_path, monkeypatch):
    path = tmp_path / "registry.json"
    path.write_text('{"active": "gbm-20261030-052117", "history": []}')
    monkeypatch.setattr(registry_module, "REGISTRY_PATH", path)
    assert registry_module.active_version() == "gbm-20261030-052117"


def test_registry_falls_back_to_the_default_when_missing_or_blank(tmp_path, monkeypatch):
    path = tmp_path / "registry.json"
    monkeypatch.setattr(registry_module, "REGISTRY_PATH", path)
    assert registry_module.active_version() == registry_module.DEFAULT_VERSION      # file missing
    path.write_text("  \n")
    assert registry_module.active_version() == registry_module.DEFAULT_VERSION      # file blank


def test_registry_rejects_versions_that_could_escape_the_models_folder(tmp_path, monkeypatch):
    path = tmp_path / "registry.json"
    monkeypatch.setattr(registry_module, "REGISTRY_PATH", path)
    path.write_text('{"active": "../../etc/passwd"}')
    with pytest.raises(ValueError, match="Unsafe"):
        registry_module.active_version()


# ---------------------------------------------------------------- dataset builder (training/build_dataset.py)

def test_find_value_matches_snake_and_camel_case_at_any_depth():
    snapshot = {"features": {"velocity": {"transactionCount5m": 3}}, "rules": [{"new_device": True}]}
    assert build_dataset.find_value(snapshot, "transaction_count_5m") == (True, 3)
    assert build_dataset.find_value(snapshot, "new_device") == (True, True)
    assert build_dataset.find_value(snapshot, "not_there") == (False, None)


def test_find_value_prefers_the_shallowest_match():
    assert build_dataset.find_value({"amount": 1, "nested": {"amount": 2}}, "amount") == (True, 1)


def test_extract_row_reads_the_snapshot_and_falls_back_to_the_export_fields():
    item = {
        "amount": 250.0,
        "occurredAt": "2026-10-03T14:30:00Z",
        "decisionInputs": {
            "averageAmount": 200.0, "historyCount": 8,
            "transactionCount5m": 1, "transactionCount1h": 2,
            "newDevice": "false", "newMerchant": True, "countryChanged": False,
        },
    }
    row, missing = build_dataset.extract_row(item)
    assert missing == []
    assert row["amount"] == 250.0                 # not in the snapshot: taken from the export
    assert row["hour_of_day"] == 14               # derived from occurredAt (UTC)
    assert row["new_device"] is False             # the string "false" must not count as True
    assert row["new_merchant"] is True
    assert row["amount_z_score"] is None          # nullable: legitimately absent for a new account
    assert len(to_vector(row)) == len(FEATURE_NAMES)


def test_extract_row_reports_which_inputs_are_missing():
    item = {"amount": 10.0, "occurredAt": "2026-10-03T14:30:00Z", "decisionInputs": {}}
    _, missing = build_dataset.extract_row(item)
    assert "average_amount" in missing and "history_count" in missing
    assert "amount" not in missing                # covered by the fallback


# ---------------------------------------------------------------- retraining helpers (training/retrain.py)

Y_VAL = np.array([0] * 10 + [1] * 10)
S_VAL = np.concatenate([np.linspace(0.1, 0.2, 10), np.linspace(0.8, 0.9, 10)])


def test_policy_calibration_anchors_are_ordered_and_inside_zero_one():
    cal = retrain.policy_calibration(Y_VAL, S_VAL, 0.5)
    assert 0 < cal["review_raw"] < cal["very_high_raw"] < 1


def test_policy_calibration_clamps_an_extreme_review_cutoff():
    cal = retrain.policy_calibration(Y_VAL, S_VAL, 0.999)
    assert cal["review_raw"] == 0.97
    assert 0.97 < cal["very_high_raw"] <= 0.99


def test_stratified_split_keeps_both_classes_where_a_time_split_would_not():
    # The demo seed creates its suspicious transactions last, so a time split starves the training set
    y = np.array([0] * 80 + [1] * 20)
    train, _, _ = retrain.make_split(y, "chronological")
    assert y[train].sum() == 0
    for part in retrain.make_split(y, "stratified"):
        assert 0 < y[part].sum() < len(part)
