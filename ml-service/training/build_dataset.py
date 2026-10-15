"""Turns the backend's labelled-transaction export into the (X, y) arrays the trainer needs.

Features are NOT recomputed. They are read from the `decisionInputs` snapshot the backend stored
when it made the decision, so the model learns from exactly what the system saw at that moment
(recomputing them today would leak information from the future into training).

The snapshot's field names are not hard-coded by position: each feature is looked up by name
(snake_case or camelCase, any nesting depth). If a name cannot be found, we say so loudly.
"""
import json
import sys
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import urlopen

import numpy as np

# Make "from app.features import ..." work when run from ml-service/
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from app.features import to_vector  # noqa: E402

FEEDBACK_DIR = Path(__file__).resolve().parent.parent / "data" / "feedback"

# Inputs to_vector() needs (same names as the ScoreRequest fields)
REQUIRED = [
    "amount", "average_amount", "history_count",
    "transaction_count_5m", "transaction_count_1h",
    "new_device", "new_merchant", "country_changed", "hour_of_day",
]
NULLABLE = ["amount_z_score", "seconds_since_previous"]     # legitimately null for a new account
BOOLEANS = {"new_device", "new_merchant", "country_changed"}


def _norm(name: str) -> str:
    """'transactionCount5m' and 'transaction_count_5m' both become 'transactioncount5m'."""
    return name.replace("_", "").lower()


def find_value(node, name):
    """Breadth-first search (shallowest match wins). Returns (found, value)."""
    target = _norm(name)
    queue = deque([node])
    while queue:
        cur = queue.popleft()
        if isinstance(cur, dict):
            for key, value in cur.items():
                if _norm(key) == target and not isinstance(value, (dict, list)):
                    return True, value
            queue.extend(cur.values())
        elif isinstance(cur, list):
            queue.extend(cur)
    return False, None


def parse_time(iso: str) -> datetime:
    return datetime.fromisoformat(iso.replace("Z", "+00:00"))


def _as_bool(value) -> bool:
    if isinstance(value, str):
        return value.strip().lower() in ("true", "1", "yes")
    return bool(value)


def extract_row(item: dict):
    """Returns (row_dict, missing_names). row_dict has the keys to_vector() expects."""
    snapshot = item["decisionInputs"]
    # Fallbacks from the export itself, if the snapshot does not contain them
    fallback = {
        "amount": item.get("amount"),
        "hour_of_day": parse_time(item["occurredAt"]).astimezone(timezone.utc).hour,
    }
    row, missing = {}, []

    for name in REQUIRED:
        found, value = find_value(snapshot, name)
        if not found or value is None:
            value = fallback.get(name)
        if value is None:
            missing.append(name)
            continue
        row[name] = _as_bool(value) if name in BOOLEANS else value

    for name in NULLABLE:
        found, value = find_value(snapshot, name)
        row[name] = value if found else None
    return row, missing


def load_labeled(api: str | None = None, path: str | None = None) -> list[dict]:
    """Reads the export from a file, or downloads it from the backend (and keeps a copy)."""
    if path:
        return json.loads(Path(path).read_text())
    with urlopen(f"{api}/api/feedback/labels", timeout=30) as response:
        items = json.loads(response.read())
    FEEDBACK_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    (FEEDBACK_DIR / f"labels-{stamp}.json").write_text(json.dumps(items, indent=2))
    return items


def build(items: list[dict]):
    """Returns X (features), y (labels), ids, plus a list of skipped rows with the reason."""
    items = sorted(items, key=lambda i: i["occurredAt"])        # oldest first
    rows, labels, ids, skipped = [], [], [], []
    for item in items:
        row, missing = extract_row(item)
        if missing:
            skipped.append((item["transactionId"], missing))
            continue
        rows.append(to_vector(row))
        labels.append(int(item["label"]))
        ids.append(item["transactionId"])

    if not rows and items:
        found = items[0]["decisionInputs"]
        keys = list(found.keys()) if isinstance(found, dict) else type(found).__name__
        raise SystemExit(
            "Could not read features from ANY snapshot.\n"
            f"  missing: {skipped[0][1]}\n"
            f"  top-level snapshot keys: {keys}\n"
            "Send me one decisionInputs object and I will fix the name mapping."
        )
    return np.asarray(rows), np.asarray(labels), ids, skipped