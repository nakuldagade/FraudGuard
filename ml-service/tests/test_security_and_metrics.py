"""Token checks on /score and /admin/*, the open routes, and the Prometheus metrics."""
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import security
from app.main import app, resolve_model_path
from tests.conftest import ADMIN_HEADERS, SERVICE_HEADERS
from tests.test_api import TYPICAL


@pytest.fixture(scope="module")
def anon():
    if not resolve_model_path().exists():
        pytest.skip("model file missing: run  python training/train.py  first")
    with TestClient(app) as c:      # no default headers: every test chooses its own
        yield c


def test_score_requires_the_service_token(anon):
    assert anon.post("/score", json=TYPICAL).status_code == 401
    assert anon.post("/score", json=TYPICAL, headers={"X-Service-Token": "wrong"}).status_code == 401
    assert anon.post("/score", json=TYPICAL, headers=SERVICE_HEADERS).status_code == 200


def test_admin_token_does_not_open_score_and_service_token_does_not_open_admin(anon):
    assert anon.post("/score", json=TYPICAL, headers=ADMIN_HEADERS).status_code == 401
    assert anon.get("/admin/registry", headers=SERVICE_HEADERS).status_code == 401


def test_admin_routes_require_the_admin_token(anon):
    assert anon.get("/admin/registry").status_code == 401
    assert anon.post("/admin/reload").status_code == 401
    assert anon.get("/admin/registry", headers=ADMIN_HEADERS).status_code == 200


def test_health_and_metrics_stay_open(anon):
    assert anon.get("/health").status_code == 200
    assert anon.get("/metrics").status_code == 200


def test_missing_token_in_environment_means_nobody_gets_in(anon, monkeypatch):
    monkeypatch.delenv("SERVICE_TOKEN")
    assert anon.post("/score", json=TYPICAL, headers=SERVICE_HEADERS).status_code == 401
    assert anon.post("/score", json=TYPICAL, headers={"X-Service-Token": ""}).status_code == 401


def test_startup_requires_tokens_outside_local(monkeypatch):
    monkeypatch.setenv("ENV", "production")
    monkeypatch.delenv("ADMIN_TOKEN")
    with pytest.raises(RuntimeError, match="ADMIN_TOKEN"):
        security.check_startup()
    monkeypatch.setenv("ADMIN_TOKEN", "x")
    monkeypatch.setenv("SERVICE_TOKEN", "y")
    security.check_startup()       # both set: fine


def test_metrics_count_scores_and_expose_the_live_model(anon):
    anon.post("/score", json=TYPICAL, headers=SERVICE_HEADERS)
    text = anon.get("/metrics").text
    assert "ml_scores_total" in text
    assert "ml_score_seconds_bucket" in text
    assert "ml_policy_score_bucket" in text
    assert 'ml_model_info{model_type=' in text and "} 1.0" in text


def test_failed_reload_returns_409_and_counts_a_failure(anon, monkeypatch):
    def boom(path):
        raise ValueError("corrupt")
    monkeypatch.setattr("app.main.load_bundle", boom)
    before = anon.get("/health").json()["model_version"]
    response = anon.post("/admin/reload", headers=ADMIN_HEADERS)
    assert response.status_code == 409
    assert anon.get("/health").json()["model_version"] == before        # the old model keeps serving
    assert 'ml_model_reloads_total{outcome="failure"}' in anon.get("/metrics").text
