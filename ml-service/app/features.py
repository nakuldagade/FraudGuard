"""Turns one scoring request into the numeric vector the model was trained on.

THE SAME FUNCTION is used by training (training/train.py) and by serving (app/model.py), so the
two can never disagree about how a feature is computed ("training/serving skew").

If you add, remove, reorder or re-define a feature, bump FEATURE_SCHEMA_VERSION and retrain:
the service refuses to load a model trained on a different schema.
"""
import math
from typing import Mapping

FEATURE_SCHEMA_VERSION = "1"

# The order here is the column order of the model's input.
FEATURE_NAMES = [
    "amount_log",                  # log(1 + amount): amounts span orders of magnitude
    "amount_to_average_ratio",     # amount / account's usual amount (1.0 when there is no history)
    "amount_z_score",              # standard deviations from usual (0.0 when there is no history)
    "transaction_count_5m",
    "transaction_count_1h",
    "log_seconds_since_previous",  # log(1 + seconds); first transaction counts as "a day ago"
    "new_device",                  # 0 / 1
    "new_merchant",                # 0 / 1
    "country_changed",             # 0 / 1
    "history_count",               # capped at 100
    "hour_of_day",                 # 0-23
]

_NO_PREVIOUS_SECONDS = 86_400      # "no previous transaction" is treated as one day ago
_Z_MIN, _Z_MAX = -10.0, 50.0       # clip extreme z-scores so a single outlier can't dominate
_RATIO_MAX = 100.0
_HISTORY_CAP = 100


def to_vector(row: Mapping) -> list[float]:
    """row: a dict with the ScoreRequest field names. Returns FEATURE_NAMES-ordered floats."""
    amount = float(row["amount"])
    average = float(row["average_amount"])
    history = int(row["history_count"])

    ratio = amount / average if history > 0 and average > 0 else 1.0
    ratio = min(ratio, _RATIO_MAX)

    z = row.get("amount_z_score")
    z = 0.0 if z is None else min(max(float(z), _Z_MIN), _Z_MAX)

    seconds = row.get("seconds_since_previous")
    seconds = _NO_PREVIOUS_SECONDS if seconds is None else int(seconds)

    return [
        math.log1p(amount),
        ratio,
        z,
        float(row["transaction_count_5m"]),
        float(row["transaction_count_1h"]),
        math.log1p(seconds),
        1.0 if row["new_device"] else 0.0,
        1.0 if row["new_merchant"] else 0.0,
        1.0 if row["country_changed"] else 0.0,
        float(min(history, _HISTORY_CAP)),
        float(row["hour_of_day"]),
    ]