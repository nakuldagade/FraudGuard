"""Synthetic data generation, model training and evaluation.   Run:  python training/train.py

IMPORTANT - read before quoting any number from this file:
  * The data is SYNTHETIC. "Fraud" here means the fraud patterns generated below (account takeover,
    card testing, one-off high-value purchase). The scores measure how well the model finds THOSE
    patterns, not how it would perform on real fraud. They say nothing about real-world accuracy.
  * Isolation Forest is UNSUPERVISED: it is fitted without labels. Labels are used only afterwards,
    to measure it and to pick an alert threshold.
  * Rows are split by TIME (oldest -> train, then validation, newest -> test) so the model is never
    evaluated on data from before the period it learned from (no temporal leakage).
"""
import json
import sys
from collections import deque
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import sklearn
from sklearn.ensemble import IsolationForest
from sklearn.metrics import average_precision_score, confusion_matrix, precision_recall_curve, roc_auc_score

# Make "from app.features import ..." work when this file is run as a script from ml-service/
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from app.features import FEATURE_NAMES, FEATURE_SCHEMA_VERSION, to_vector  # noqa: E402

MODEL_VERSION = "isoforest-v1"
SEED = 42
N_ACCOUNTS = 400
DAYS = 90
FRAUD_ACCOUNT_RATE = 0.25          # share of accounts that suffer one fraud episode
MIN_HISTORY = 5                    # earlier transactions needed for a z-score (same as the Redis script)
COUNTRIES = ["IN", "US", "GB", "DE", "SG", "AE"]

MODELS_DIR = Path(__file__).resolve().parent.parent / "models"


# ----------------------------------------------------------------------------------------------
# 1) Features per account. This mirrors backend/src/main/resources/redis/record_transaction.lua,
#    so training data is computed the same way as live data.
# ----------------------------------------------------------------------------------------------
class AccountState:
    ONE_HOUR = 3600
    FIVE_MIN = 300

    def __init__(self):
        self.window = deque()              # (timestamp, amount) of the last hour
        self.n = 0                         # transactions in the long-term baseline
        self.mean = 0.0
        self.m2 = 0.0                      # Welford's running sum of squared deviations
        self.devices = set()
        self.merchants = set()
        self.last_ts = None
        self.last_country = None

    def observe(self, ts, amount, device, merchant, country):
        """Returns this transaction's features (as the live system would see them), then records it."""
        while self.window and self.window[0][0] <= ts - self.ONE_HOUR:
            self.window.popleft()
        self.window.append((ts, amount))
        count_1h = len(self.window)
        count_5m = sum(1 for t, _ in self.window if t > ts - self.FIVE_MIN)

        # Baseline BEFORE adding this amount: compare the transaction with the past, not with itself
        std = (self.m2 / (self.n - 1)) ** 0.5 if self.n > 1 else 0.0
        z = (amount - self.mean) / std if self.n >= MIN_HISTORY and std > 0 else None

        seconds = None if self.last_ts is None else int(ts - self.last_ts)
        country_changed = bool(self.last_country and country and country != self.last_country)

        row = {
            "amount": amount,
            "hour_of_day": int((ts // 3600) % 24),
            "transaction_count_5m": count_5m,
            "transaction_count_1h": count_1h,
            "history_count": self.n,
            "average_amount": self.mean if self.n else 0.0,
            "amount_z_score": z,
            "seconds_since_previous": seconds,
            "new_device": device not in self.devices,
            "new_merchant": merchant not in self.merchants,
            "country_changed": country_changed,
        }

        # Record the transaction
        self.n += 1
        delta = amount - self.mean
        self.mean += delta / self.n
        self.m2 += delta * (amount - self.mean)
        self.devices.add(device)
        self.merchants.add(merchant)
        self.last_ts = ts
        self.last_country = country
        return row


# ----------------------------------------------------------------------------------------------
# 2) Synthetic transactions: normal behaviour per account, plus labelled fraud episodes.
# ----------------------------------------------------------------------------------------------
def generate_events(n_accounts=N_ACCOUNTS, days=DAYS, seed=SEED):
    """Returns a list of raw transactions, each with a 0/1 fraud label. Time is in seconds."""
    rng = np.random.default_rng(seed)
    events = []

    def add(ts, acct, amount, device, merchant, country, label):
        events.append({"ts": int(ts), "account": acct, "amount": max(round(float(amount), 2), 1.0),
                       "device": device, "merchant": merchant, "country": country, "label": label})

    for a in range(n_accounts):
        acct = f"acc-{a}"
        mu = rng.normal(6.0, 0.6)                    # this account's typical amount: exp(mu)
        typical = float(np.exp(mu))
        peak_hour = int(rng.integers(9, 21))
        devices = [f"{acct}-dev-{i}" for i in range(int(rng.integers(1, 3)))]
        merchants = [f"{acct}-m-{i}" for i in range(int(rng.integers(4, 9)))]
        home = str(rng.choice(COUNTRIES))

        # Normal behaviour, with a little harmless noise (a new device, a new shop, a trip abroad)
        for _ in range(int(rng.poisson(1.5 * days))):
            ts = int(rng.integers(0, days)) * 86400 + int(np.clip(rng.normal(peak_hour, 3.0), 0, 23)) * 3600 \
                 + int(rng.integers(0, 3600))
            device = str(rng.choice(devices)) if rng.random() > 0.015 else f"{acct}-dev-new-{int(rng.integers(1000))}"
            merchant = str(rng.choice(merchants)) if rng.random() > 0.15 else f"m-shared-{int(rng.integers(50))}"
            country = home if rng.random() > 0.01 else str(rng.choice(COUNTRIES))
            amount = np.exp(rng.normal(mu, 0.35))
            if rng.random() < 0.02:                       # a legitimate large purchase (3-6x usual)
                amount = typical * rng.uniform(3, 6)
            add(ts, acct, amount, device, merchant, country, 0)

        # Legitimate shopping sprees: several ordinary purchases within a few minutes (NOT fraud)
        for _ in range(int(rng.poisson(days / 15))):
            t_spree = int(rng.integers(0, days)) * 86400 + int(np.clip(rng.normal(peak_hour, 3.0), 0, 23)) * 3600
            for off in sorted(rng.integers(0, 600, size=int(rng.integers(3, 7)))):
                add(t_spree + int(off), acct, np.exp(rng.normal(mu, 0.35)), devices[0],
                    str(rng.choice(merchants)), home, 0)

        # One labelled fraud episode for a share of the accounts. Severity varies: some episodes
        # are blatant, others deliberately subtle (close to normal behaviour), like real fraud.
        if rng.random() < FRAUD_ACCOUNT_RATE:
            t0 = int(rng.integers(7 * 86400, days * 86400 - 3600))
            kind = str(rng.choice(["takeover", "card_testing", "high_value"]))
            subtle = rng.random() < 0.5
            foreign = str(rng.choice([c for c in COUNTRIES if c != home]))
            stolen_device = f"{acct}-dev-stolen-{int(rng.integers(1000))}"
            if kind == "takeover":          # new device, a few large purchases in minutes
                low, high = (1.5, 3.5) if subtle else (3, 8)
                for off in sorted(rng.integers(0, 900 if subtle else 600, size=int(rng.integers(2 if subtle else 3, 5 if subtle else 7)))):
                    add(t0 + int(off), acct, typical * rng.uniform(low, high), stolen_device,
                        f"m-fraud-{int(rng.integers(500))}",
                        home if subtle and rng.random() < 0.6 else foreign, 1)
            elif kind == "card_testing":    # many small purchases at new shops in a few minutes
                low, high = (10, 60) if subtle else (1, 20)
                for off in sorted(rng.integers(0, 300, size=int(rng.integers(5 if subtle else 8, 10 if subtle else 16)))):
                    add(t0 + int(off), acct, rng.uniform(low, high), stolen_device,
                        f"m-fraud-{int(rng.integers(500))}", foreign if rng.random() < 0.5 else home, 1)
            else:                           # one large purchase
                low, high = (4, 9) if subtle else (10, 30)
                add(t0, acct, typical * rng.uniform(low, high),
                    stolen_device if rng.random() < 0.5 else devices[0],
                    f"m-fraud-{int(rng.integers(500))}", home, 1)
    return events


def build_dataset(n_accounts=N_ACCOUNTS, days=DAYS, seed=SEED):
    """Generates events, replays them in time order to compute features, returns arrays."""
    events = sorted(generate_events(n_accounts, days, seed), key=lambda e: (e["ts"], e["account"]))
    states, rows = {}, []
    for e in events:
        state = states.setdefault(e["account"], AccountState())
        rows.append(state.observe(e["ts"], e["amount"], e["device"], e["merchant"], e["country"]))
    X = np.asarray([to_vector(r) for r in rows])
    y = np.asarray([e["label"] for e in events])
    ts = np.asarray([e["ts"] for e in events])
    return X, y, ts


def chronological_split(n, train=0.70, validation=0.15):
    """Rows are already sorted by time: oldest -> train, middle -> validation, newest -> test."""
    a, b = int(n * train), int(n * (train + validation))
    return slice(0, a), slice(a, b), slice(b, n)


# ----------------------------------------------------------------------------------------------
# 3) Train and evaluate
# ----------------------------------------------------------------------------------------------
def anomaly_scores(model, X):
    return -model.score_samples(X)      # higher = more unusual (same convention as app/model.py)


def best_f1_threshold(y_true, scores):
    precision, recall, thresholds = precision_recall_curve(y_true, scores)
    f1 = 2 * precision[:-1] * recall[:-1] / np.maximum(precision[:-1] + recall[:-1], 1e-12)
    return float(thresholds[int(np.argmax(f1))])


def evaluate(y_true, scores, threshold):
    flagged = scores >= threshold
    tn, fp, fn, tp = confusion_matrix(y_true, flagged, labels=[0, 1]).ravel()
    return {
        "rows": int(len(y_true)),
        "fraud_rows": int(y_true.sum()),
        "fraud_rate": round(float(y_true.mean()), 5),
        "pr_auc": round(float(average_precision_score(y_true, scores)), 4),   # random guessing = fraud_rate
        "roc_auc": round(float(roc_auc_score(y_true, scores)), 4),
        "threshold": round(float(threshold), 4),
        "confusion_matrix": {"true_negative": int(tn), "false_positive": int(fp),
                             "false_negative": int(fn), "true_positive": int(tp)},
        "precision": round(float(tp / max(tp + fp, 1)), 4),
        "recall": round(float(tp / max(tp + fn, 1)), 4),
        "false_positive_rate": round(float(fp / max(fp + tn, 1)), 5),
        "alert_rate": round(float(flagged.mean()), 5),
    }


def main():
    X, y, ts = build_dataset()
    train, val, test = chronological_split(len(y))
    print(f"{len(y)} transactions, {int(y.sum())} fraud ({y.mean():.2%}); "
          f"train {train.stop} / validation {val.stop - val.start} / test {test.stop - test.start}")

    # UNSUPERVISED fit: no labels are passed. The training slice even contains some (unlabelled)
    # fraud, like real data would.
    model = IsolationForest(n_estimators=200, contamination="auto", random_state=SEED, n_jobs=-1)
    model.fit(X[train])

    s_val, s_test = anomaly_scores(model, X[val]), anomaly_scores(model, X[test])
    threshold = best_f1_threshold(y[val], s_val)       # chosen on VALIDATION data only
    report = {
        "model_version": MODEL_VERSION,
        "feature_schema_version": FEATURE_SCHEMA_VERSION,
        "data": "SYNTHETIC - measures detection of the generated fraud patterns only, not real-world accuracy",
        "split": "chronological: 70% oldest train, 15% validation, 15% newest test",
        "validation": evaluate(y[val], s_val, threshold),
        "test": evaluate(y[test], s_test, threshold),
    }

    MODELS_DIR.mkdir(exist_ok=True)
    joblib.dump({
        "model": model,
        "model_version": MODEL_VERSION,
        "feature_schema_version": FEATURE_SCHEMA_VERSION,
        "feature_names": FEATURE_NAMES,
        "metadata": {
            "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "sklearn_version": sklearn.__version__,
            "seed": SEED,
            "training_rows": int(train.stop),
            "suggested_alert_threshold": report["test"]["threshold"],
            "evaluation": report,
        },
    }, MODELS_DIR / f"{MODEL_VERSION}.joblib")
    (MODELS_DIR / f"{MODEL_VERSION}.metrics.json").write_text(json.dumps(report, indent=2) + "\n")

    print(json.dumps(report, indent=2))
    print(f"\nSaved {MODELS_DIR / (MODEL_VERSION + '.joblib')}")


if __name__ == "__main__":
    main()