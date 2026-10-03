"""API tests. They use the trained model, so run `python training/train.py` first."""
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import DEFAULT_MODEL_PATH, app


@pytest.fixture(scope="module")
def client():
    if not Path(DEFAULT_MODEL_PATH).exists():
        pytest.skip("model file missing: run  python training/train.py  first")
    with TestClient(app) as c:      # "with" runs the startup code that loads the model
        yield c


TYPICAL = dict(transaction_id="t-typical", amount=400.0, hour_of_day=14, transaction_count_5m=1,
               transaction_count_1h=1, history_count=60, average_amount=400.0, amount_z_score=0.1,
               seconds_since_previous=40_000, new_device=False, new_merchant=False, country_changed=False)

SUSPICIOUS = dict(transaction_id="t-suspicious", amount=20_000.0, hour_of_day=3, transaction_count_5m=8,
                  transaction_count_1h=9, history_count=60, average_amount=400.0, amount_z_score=40.0,
                  seconds_since_previous=5, new_device=True, new_merchant=True, country_changed=True)


def test_health_reports_the_loaded_model(client):
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["model_version"] == "isoforest-v1"
    assert body["feature_schema_version"] == "1"


def test_score_returns_score_and_model_version(client):
    body = client.post("/score", json=TYPICAL).json()
    assert body["transaction_id"] == "t-typical"
    assert 0.0 < body["anomaly_score"] < 1.0
    assert body["model_version"] == "isoforest-v1"


def test_suspicious_transaction_scores_higher_than_typical_one(client):
    typical = client.post("/score", json=TYPICAL).json()["anomaly_score"]
    suspicious = client.post("/score", json=SUSPICIOUS).json()["anomaly_score"]
    assert suspicious > typical + 0.05


def test_missing_history_fields_are_accepted(client):
    first_ever = dict(TYPICAL, history_count=0, average_amount=0.0, amount_z_score=None,
                      seconds_since_previous=None)
    assert client.post("/score", json=first_ever).status_code == 200


def test_invalid_request_is_rejected(client):
    bad = dict(TYPICAL, amount=-5, hour_of_day=99)
    response = client.post("/score", json=bad)
    assert response.status_code == 422
    assert "detail" in response.json()