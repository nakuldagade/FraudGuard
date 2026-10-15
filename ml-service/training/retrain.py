"""Feedback-loop retraining: learn from analyst labels, compare with the live model, promote only if better.

Run from ml-service/ with the venv active:
    python training/retrain.py                       # dry run: train a challenger, compare, write a report
    python training/retrain.py --promote             # also make it the active model if it passes the gate
    python training/retrain.py --split stratified    # demo data only (see below)

READ BEFORE QUOTING ANY NUMBER:
  * Labels come from analysts reviewing mostly REVIEW/BLOCKED transactions (selection bias). With the
    demo seed script the labels are SYNTHETIC (derived from the scenario that generated each row), so
    the metrics show the loop works, not real-world accuracy.
  * Models are compared by PR-AUC, which depends only on how each model RANKS transactions, so it is
    fair across different score scales (anomaly score vs probability). It is measured on a held-out
    test slice that neither model was fitted or tuned on.
  * Promotion changes the registry file only. The serving code starts using the registry in Part 9.3.
"""
import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import sklearn
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.model_selection import train_test_split
from sklearn.utils.class_weight import compute_sample_weight

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
# Running "python training/retrain.py" already puts training/ first on the path, so these resolve:
from app.features import FEATURE_NAMES, FEATURE_SCHEMA_VERSION  # noqa: E402
from build_dataset import build, load_labeled  # noqa: E402
from train import best_f1_threshold, evaluate  # noqa: E402

SEED = 42
MODELS_DIR = ROOT / "models"
REGISTRY = MODELS_DIR / "registry.json"

# ---- Promotion gate ----
MIN_LABELS = 60              # fewer labelled rows than this: refuse to train at all
MIN_PER_CLASS = 15           # need at least this many fraud AND legitimate examples
MIN_PR_AUC_GAIN = 0.02       # challenger must beat the active model by this much on the test slice


def load_registry() -> dict:
    if REGISTRY.exists():
        return json.loads(REGISTRY.read_text())
    return {"active": "isoforest-v1", "history": []}


def score(bundle: dict, X) -> np.ndarray:
    """Higher = more suspicious, for either kind of model."""
    model = bundle["model"]
    if bundle.get("model_type") == "gradient_boosting":
        return model.predict_proba(X)[:, 1]          # probability of fraud
    return -model.score_samples(X)                   # Isolation Forest anomaly score


def make_split(y, mode: str):
    """Returns index arrays (train, validation, test), about 60 / 20 / 20."""
    idx = np.arange(len(y))
    if mode == "chronological":
        a, b = int(len(y) * 0.6), int(len(y) * 0.8)
        return idx[:a], idx[a:b], idx[b:]
    # Stratified keeps the fraud share equal in every slice. Needed for the demo data, where the
    # suspicious transactions are all created last and a time split would leave none for training.
    train, rest = train_test_split(idx, test_size=0.4, stratify=y, random_state=SEED)
    val, test = train_test_split(rest, test_size=0.5, stratify=y[rest], random_state=SEED)
    return np.sort(train), np.sort(val), np.sort(test)


def require_both_classes(name: str, y) -> None:
    if y.sum() == 0 or y.sum() == len(y):
        raise SystemExit(
            f"The {name} slice has only one class ({int(y.sum())} fraud of {len(y)}), so it cannot be "
            "evaluated. For demo data try:  python training/retrain.py --split stratified"
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("--api", default="http://localhost:8080", help="backend base URL")
    parser.add_argument("--from-file", help="use a saved export instead of calling the backend")
    parser.add_argument("--split", choices=["chronological", "stratified"], default="chronological")
    parser.add_argument("--promote", action="store_true", help="activate the challenger if it passes the gate")
    args = parser.parse_args()

    # 1) Labelled data from the backend
    items = load_labeled(api=args.api, path=args.from_file)
    X, y, ids, skipped = build(items)
    n_fraud, n_legit = int(y.sum()), int(len(y) - y.sum())
    print(f"{len(items)} labelled rows exported, {len(y)} usable "
          f"({n_fraud} fraud, {n_legit} legitimate), {len(skipped)} skipped")
    if skipped:
        print(f"  e.g. {skipped[0][0]} is missing {skipped[0][1]}")

    # 2) Minimum-data gate
    if len(y) < MIN_LABELS or min(n_fraud, n_legit) < MIN_PER_CLASS:
        print(f"\nNot enough feedback to retrain safely. Need at least {MIN_LABELS} labelled rows and "
              f"{MIN_PER_CLASS} of each class. Review more transactions (or run the seed script with "
              "--label, Part 9.4) and try again.")
        raise SystemExit(2)

    train, val, test = make_split(y, args.split)
    for name, part in (("validation", val), ("test", test)):
        require_both_classes(name, y[part])
    require_both_classes("training", y[train])
    print(f"split ({args.split}): train {len(train)} / validation {len(val)} / test {len(test)}")
    if y[test].sum() < 5:
        print("  WARNING: fewer than 5 fraud rows in the test slice, so the comparison is very noisy")

    # 3) Challenger: a supervised model that learns directly from the analyst labels.
    #    Class weights stop it from ignoring the rarer class.
    challenger = GradientBoostingClassifier(
        n_estimators=150, max_depth=3, learning_rate=0.1, subsample=0.8, random_state=SEED)
    challenger.fit(X[train], y[train], sample_weight=compute_sample_weight("balanced", y[train]))
    version = "gbm-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    chal_bundle = {"model": challenger, "model_type": "gradient_boosting"}

    # 4) The model currently live, scored on the SAME slices
    registry = load_registry()
    active_version = registry["active"]
    active_bundle = joblib.load(MODELS_DIR / f"{active_version}.joblib")

    def evaluate_on_test(bundle):
        # The alert threshold is tuned on validation only; the test slice is touched once.
        threshold = best_f1_threshold(y[val], score(bundle, X[val]))
        return evaluate(y[test], score(bundle, X[test]), threshold)

    chal_eval, active_eval = evaluate_on_test(chal_bundle), evaluate_on_test(active_bundle)

    # 5) Promotion decision
    gain = round(chal_eval["pr_auc"] - active_eval["pr_auc"], 4)
    passes = gain >= MIN_PR_AUC_GAIN
    report = {
        "challenger_version": version,
        "active_version": active_version,
        "data": "analyst labels; SYNTHETIC if produced by the demo seed script. Not real-world accuracy.",
        "labels": {"usable": int(len(y)), "fraud": n_fraud, "legitimate": n_legit, "skipped": len(skipped)},
        "split": args.split,
        "test_slice": {"rows": int(len(test)), "fraud_rows": int(y[test].sum())},
        "challenger": chal_eval,
        "active": active_eval,
        "pr_auc_gain": gain,
        "required_gain": MIN_PR_AUC_GAIN,
        "passes_gate": passes,
        "feature_importance": dict(sorted(
            zip(FEATURE_NAMES, challenger.feature_importances_.round(4).tolist()),
            key=lambda kv: -kv[1])),
    }

    # 6) Save the candidate next to the live model. NEVER overwrite an existing version.
    MODELS_DIR.mkdir(exist_ok=True)
    joblib.dump({
        "model": challenger,
        "model_type": "gradient_boosting",
        "model_version": version,
        "feature_schema_version": FEATURE_SCHEMA_VERSION,
        "feature_names": FEATURE_NAMES,
        "metadata": {
            "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "sklearn_version": sklearn.__version__,
            "seed": SEED,
            "training_rows": int(len(train)),
            "suggested_alert_threshold": chal_eval["threshold"],
            "evaluation": report,
        },
    }, MODELS_DIR / f"{version}.joblib")
    (MODELS_DIR / f"{version}.metrics.json").write_text(json.dumps(report, indent=2) + "\n")

    print(json.dumps(report, indent=2))
    print(f"\nSaved candidate {version}")

    # 7) Promote (registry only) when the gate passes AND --promote was given
    if passes and args.promote:
        registry["history"].append({
            "version": version,
            "previous": active_version,
            "promoted_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "pr_auc_gain": gain,
        })
        registry["active"] = version
        REGISTRY.write_text(json.dumps(registry, indent=2) + "\n")
        print(f"PROMOTED: active model is now {version} (was {active_version})")
    elif passes:
        print("Gate passed. Re-run with --promote to activate it.")
    else:
        print(f"Gate NOT passed (gain {gain}, needs {MIN_PR_AUC_GAIN}). Active model stays {active_version}.")


if __name__ == "__main__":
    main()