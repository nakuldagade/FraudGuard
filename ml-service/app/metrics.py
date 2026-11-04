"""Prometheus metrics for the ML service (scraped at GET /metrics)."""
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Gauge, Histogram, generate_latest

SCORES = Counter("ml_scores", "Transactions scored")                       # exposed as ml_scores_total
SCORE_SECONDS = Histogram("ml_score_seconds", "Time to score one transaction")
# The score on the policy scale (0 to 1). Its shape shows drift: if it moves, inputs or the model changed.
POLICY_SCORE = Histogram("ml_policy_score", "Score returned to the backend",
                         buckets=(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.66, 0.7, 0.8, 0.9, 1.0))
RELOADS = Counter("ml_model_reloads", "Model hot-reload attempts", ["outcome"])   # success / failure
MODEL_INFO = Gauge("ml_model_info", "1 for the model currently served", ["model_version", "model_type"])


def set_live_model(version: str, model_type: str) -> None:
    """Keep exactly one ml_model_info series at 1: the model being served."""
    MODEL_INFO.clear()
    MODEL_INFO.labels(model_version=version, model_type=model_type).set(1)


def render() -> tuple[bytes, str]:
    return generate_latest(), CONTENT_TYPE_LATEST
