"""FraudGuard ML scoring service: POST /score returns an anomaly score for one transaction."""
import json
import os
from contextlib import asynccontextmanager
from pathlib import Path

import time

from fastapi import Depends, FastAPI, HTTPException, Request, Response

from app import metrics, security
from app.model import ModelBundle, anomaly_score, load_bundle
from app.registry import DEFAULT_VERSION, REGISTRY_PATH, active_version, model_path
from app.schemas import ScoreRequest, ScoreResponse


def resolve_model_path() -> Path:
    """MODEL_PATH (environment variable) wins; otherwise the model the registry marks as active."""
    override = os.environ.get("MODEL_PATH")
    return Path(override) if override else model_path(active_version())


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Runs once at startup. If the model is missing or incompatible, the service refuses to
    # start, which is better than answering requests with a broken model.
    security.check_startup()
    app.state.bundle = load_bundle(resolve_model_path())
    metrics.set_live_model(app.state.bundle.model_version, app.state.bundle.model_type)
    yield


app = FastAPI(title="FraudGuard ML service", lifespan=lifespan)


@app.get("/health")
def health(request: Request):
    bundle: ModelBundle = request.app.state.bundle
    return {
        "status": "ok",
        "model_version": bundle.model_version,
        "model_type": bundle.model_type,
        "calibrated_to_policy_scale": bundle.calibration is not None,
        "feature_schema_version": bundle.feature_schema_version,
    }


@app.get("/metrics")
def prometheus_metrics():
    body, content_type = metrics.render()
    return Response(content=body, media_type=content_type)


@app.get("/admin/registry", dependencies=[Depends(security.require_admin)])
def registry(request: Request):
    """Read-only: which model is loaded right now, which one the registry file says should be
    active, and the promotion history. The dashboard (via the Spring Boot backend) shows this.
    If loaded != active, a promotion happened but POST /admin/reload has not been called yet."""
    bundle: ModelBundle = request.app.state.bundle
    data = {}
    text = REGISTRY_PATH.read_text().strip() if REGISTRY_PATH.exists() else ""
    if text:
        data = json.loads(text)
    return {
        "loaded": bundle.model_version,
        "active": data.get("active", DEFAULT_VERSION),
        "history": data.get("history", []),
    }


@app.post("/admin/reload", dependencies=[Depends(security.require_admin)])
def reload_model(request: Request):
    """Switch to whatever the registry now marks as active, without restarting the service.
    If the new model fails to load, the current model keeps serving.
    Requires the X-Admin-Token header."""
    current: ModelBundle = request.app.state.bundle
    try:
        new_bundle = load_bundle(resolve_model_path())
    except Exception as e:
        metrics.RELOADS.labels(outcome="failure").inc()
        raise HTTPException(
            status_code=409,
            detail=f"Reload failed, still serving {current.model_version}: {e}")
    request.app.state.bundle = new_bundle          # one assignment: in-flight requests are unaffected
    metrics.set_live_model(new_bundle.model_version, new_bundle.model_type)
    metrics.RELOADS.labels(outcome="success").inc()
    return {"previous": current.model_version, "active": new_bundle.model_version}


@app.post("/score", response_model=ScoreResponse, dependencies=[Depends(security.require_service)])
def score(payload: ScoreRequest, request: Request) -> ScoreResponse:
    bundle: ModelBundle = request.app.state.bundle
    started = time.perf_counter()
    value = round(anomaly_score(bundle, payload.model_dump()), 6)
    metrics.SCORE_SECONDS.observe(time.perf_counter() - started)
    metrics.POLICY_SCORE.observe(value)
    metrics.SCORES.inc()
    return ScoreResponse(
        transaction_id=payload.transaction_id,
        anomaly_score=value,
        model_version=bundle.model_version,
        feature_schema_version=bundle.feature_schema_version,
    )