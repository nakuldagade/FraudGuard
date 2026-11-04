# ADR 0007: Feedback loop with a promotion gate and score calibration

Status: accepted. Date: 2026-11-04.

## Context
Analyst verdicts are the only ground truth the system gets. They should improve the model, but a model trained on a few biased labels can easily be
worse than the one it replaces, and a new model's scores sit on a different scale than the policy's thresholds.

## Decision
`retrain.py` trains a challenger on the labelled data, compares it with the live model on a held-out slice and **promotes it only if it passes a gate**:
at least 60 labels, 15 per class, and a PR-AUC gain of at least 0.02. Promotion updates `registry.json`; `POST /admin/reload` makes the running service switch without
a restart, and a corrupt registry is refused with the old model kept (drill 9). A promoted model's scores are mapped onto the policy's scale so the thresholds keep
their meaning.

## Consequences
- A bad retrain does not reach production by accident; rollback is editing `active` in the registry and reloading.
- Each transaction records the model version that scored it, so history stays interpretable.
- In the demo the labels are **synthetic** (derived from the scenario that generated each row), so the measured gain describes the generator's patterns, not real fraud. Real labels would also be biased toward what analysts see (REVIEW and BLOCKED). See [feedback-loop.md](../feedback-loop.md).
- Retraining is manual. There is no schedule and no drift monitoring.
- Model files are not in git ([architecture.md](../architecture.md#ml-model-artifacts)).

## Alternatives considered
- **Always promote the newest model.** Fast, unsafe.
- **Online learning.** Harder to audit and to roll back.
- **A model-registry product (MLflow).** Appropriate at scale; a small JSON file is enough to show the idea.
