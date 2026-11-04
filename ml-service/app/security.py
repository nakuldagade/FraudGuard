"""Shared-secret checks for the service-to-service routes.

/score needs X-Service-Token (the Spring Boot backend sends it).
/admin/* needs X-Admin-Token (a human operator or the retraining script sends it).
/health and /metrics stay open so probes and Prometheus keep working.

The tokens are read from the environment on every request, so tests can change them. A missing
token means "nobody gets in" (fail closed), never "everybody gets in".
"""
import os
import secrets

from fastapi import Header, HTTPException


def is_local() -> bool:
    return os.environ.get("ENV", "local").lower() == "local"


def check_startup() -> None:
    """Outside local development the tokens are mandatory: refuse to start without them."""
    if is_local():
        return
    missing = [name for name in ("ADMIN_TOKEN", "SERVICE_TOKEN") if not os.environ.get(name)]
    if missing:
        raise RuntimeError(f"ENV={os.environ.get('ENV')}: {', '.join(missing)} must be set")


def _matches(expected: str | None, supplied: str | None) -> bool:
    if not expected or not supplied:
        return False
    # compare_digest takes the same time however many characters match, so the token can't be guessed
    # one character at a time from response times.
    return secrets.compare_digest(expected.encode(), supplied.encode())


def require_admin(x_admin_token: str | None = Header(default=None)) -> None:
    if not _matches(os.environ.get("ADMIN_TOKEN"), x_admin_token):
        raise HTTPException(status_code=401, detail="Missing or invalid admin token")


def require_service(x_service_token: str | None = Header(default=None)) -> None:
    if not _matches(os.environ.get("SERVICE_TOKEN"), x_service_token):
        raise HTTPException(status_code=401, detail="Missing or invalid service token")
