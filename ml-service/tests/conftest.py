"""Test setup: fixed tokens so the protected routes can be called in tests."""
import os
from pathlib import Path

os.environ["SERVICE_TOKEN"] = "test-service-token"
os.environ["ADMIN_TOKEN"] = "test-admin-token"
os.environ["ENV"] = "local"
# Pin the model so tests don't depend on which model the registry currently marks as active
os.environ["MODEL_PATH"] = str(Path(__file__).resolve().parent.parent / "models" / "isoforest-v1.joblib")

SERVICE_HEADERS = {"X-Service-Token": "test-service-token"}
ADMIN_HEADERS = {"X-Admin-Token": "test-admin-token"}
