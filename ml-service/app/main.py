"""FraudGuard ML scoring service: POST /score returns an anomaly score for one transaction."""
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request

from app.model import ModelBundle, anomaly_score, load_bundle
from app.schemas import ScoreRequest, ScoreResponse

# Where the trained model lives. Override with the MODEL_PATH environment variable.
DEFAULT_MODEL_PATH = Path(__file__).resolve().parent.parent / "models" / "isoforest-v1.joblib"


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Runs once at startup. If the model is missing or incompatible, the service refuses to
    # start, which is better than answering requests with a broken model.
    app.state.bundle = load_bundle(Path(os.environ.get("MODEL_PATH", DEFAULT_MODEL_PATH)))
    yield


app = FastAPI(title="FraudGuard ML service", lifespan=lifespan)


@app.get("/health")
def health(request: Request):
    bundle: ModelBundle = request.app.state.bundle
    return {
        "status": "ok",
        "model_version": bundle.model_version,
        "feature_schema_version": bundle.feature_schema_version,
    }


@app.post("/score", response_model=ScoreResponse)
def score(payload: ScoreRequest, request: Request) -> ScoreResponse:
    bundle: ModelBundle = request.app.state.bundle
    return ScoreResponse(
        transaction_id=payload.transaction_id,
        anomaly_score=round(anomaly_score(bundle, payload.model_dump()), 6),
        model_version=bundle.model_version,
        feature_schema_version=bundle.feature_schema_version,
    )