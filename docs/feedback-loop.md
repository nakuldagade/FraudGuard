# Feedback loop (Phase 9)

Analysts review transactions in the dashboard. Those verdicts become training labels. A retraining
script learns from them, compares the result with the model that is live, and replaces the live model
only if the new one is measurably better. Every decision records which model produced it.

> **Read this first.** The demo runs on synthetic data. The labels created by
> `npm run seed -- --label` are generated from the scenario that produced each transaction, not by a
> human. Metrics produced from them show that the loop works end to end. They say nothing about
> accuracy on real fraud.

## How it flows

~~~text
analyst clicks "Confirm fraud" / "Mark legitimate"      (dashboard -> POST /api/transactions/{id}/review)
        |
        v
analyst_reviews table  --->  GET /api/feedback/labels   (latest verdict per transaction + the stored
        |                                                 decision inputs, i.e. the features as they
        |                                                 were when the system decided)
        v
python training/retrain.py     train a challenger, compare with the live model on a held-out slice
        |
        +-- gate NOT passed -> candidate saved for inspection, live model unchanged
        |
        +-- gate passed + --promote -> models/registry.json updated
                    |
                    v
          POST /admin/reload  -> ML service serves the new model, no restart
                    |
                    v
          new transactions are scored by it; each stores its model_version
~~~

## Label rules

| Latest analyst action | Label |
|---|---|
| Confirm fraud | 1 (fraud) |
| Mark legitimate | 0 (legitimate) |
| Request further review | none yet |
| no action at all | none (not the same as "legitimate") |

The newest review of a transaction is its verdict, so an analyst who changes their mind replaces the
earlier label. Transactions without a usable decision snapshot are skipped.

## Why features are read from the stored snapshot

Each decision stores a JSON snapshot of its inputs (`decision_inputs`). Training reads features from
that snapshot instead of recomputing them today. Recomputing would let information from after the
decision leak into training, and the model would be learning from data it could not have had at the time.

## The promotion gate

`ml-service/training/retrain.py` refuses to train, or refuses to promote, unless:

1. **Enough data:** at least 60 labelled rows, with at least 15 fraud and 15 legitimate.
2. **Usable splits:** the validation and test slices each contain both classes.
3. **Real improvement:** the challenger's PR-AUC on the held-out test slice beats the live model's by
   at least 0.02.

Details worth knowing:

- Models are compared by PR-AUC because it only looks at how a model *ranks* transactions. The live
  Isolation Forest outputs an anomaly score and the challenger outputs a probability, so raw scores are
  not comparable.
- Alert thresholds are tuned on the validation slice. The test slice is used once.
- PR-AUC rewards the very top of the ranking. A challenger can look better at its own operating point
  and on ROC-AUC and still fail the gate. That is a deliberate, conservative choice.
- Promotion needs the explicit `--promote` flag. Without it the script only reports.
- Candidates are saved as new files (`models/gbm-<timestamp>.joblib` plus `.metrics.json`). An
  existing version is never overwritten.

## Putting a new model's scores on the policy's scale

The decision policy (`fraudguard.decision.ml-review-at` = 0.66, `ml-very-high-at` = 0.70 in
`application.yml`) was written for Isolation Forest anomaly scores. A probability from a supervised
model means something different at 0.66. To keep policy-v1 meaning what it says, each promoted model
ships with two anchors computed on validation data only:

- its own review cutoff (best-F1 threshold) maps to the policy's review threshold,
- its own "very high" cutoff (lowest threshold with at least 90% precision) maps to the policy's
  very-high threshold.

The ML service applies a monotone piecewise-linear map, so the order of scores never changes. The
Isolation Forest has no anchors and passes through unchanged. If you change the policy thresholds,
set `POLICY_ML_REVIEW_AT` and `POLICY_ML_VERY_HIGH_AT` for the ML service to the same values.

## Operating it

Start both services with the variables from `.env` loaded (`set -a; source .env; set +a`, see
[observability.md](observability.md#starting-and-stopping-the-stack) for the full start-up), then:

~~~bash
# 1. generate demo traffic and synthetic labels  (frontend/; logs in as the seed user)
npm run seed -- --label

# 2. train a challenger and see the report        (ml-service/, venv active)
#    The label export is ADMIN-only: retrain.py logs in with FRAUDGUARD_ADMIN_USERNAME / _PASSWORD.
python training/retrain.py --split stratified

# 3. promote it if the gate passed, then load it into the running service
python training/retrain.py --split stratified --promote
curl -X POST http://127.0.0.1:8000/admin/reload -H "X-Admin-Token: $ADMIN_TOKEN"
~~~

`--split stratified` is for the demo data only. The seed creates its suspicious transactions last, so
a time-ordered split would leave no fraud in the training slice. With real data keep the default
chronological split.

**Rollback:** set `"active"` in `ml-service/models/registry.json` back to the previous version and call
`/admin/reload` again. Rows scored in the meantime keep the version that actually scored them.

The dashboard's **Model feedback** card shows label counts, how many transactions each model version
scored, which model is live, and the promotion history. It warns when the registry and the running
service disagree, which means a promotion happened without a reload.

## Endpoints and files

| What | Where |
|---|---|
| Labelled training rows | `GET /api/feedback/labels` (ADMIN only) |
| Label counts | `GET /api/feedback/stats` (ANALYST or ADMIN) |
| Transactions scored per model | `GET /api/feedback/models` (ANALYST or ADMIN) |
| Live model and promotion history | `GET /api/feedback/registry` (ANALYST or ADMIN; proxied from the ML service) |
| ML service: loaded vs registry model | `GET /admin/registry` (header `X-Admin-Token`) |
| ML service: switch model | `POST /admin/reload` (header `X-Admin-Token`) |
| Retraining and gate | `ml-service/training/retrain.py` |
| Feature extraction from snapshots | `ml-service/training/build_dataset.py` |
| Registry file | `ml-service/models/registry.json` |
| Serving and calibration | `ml-service/app/model.py`, `ml-service/app/registry.py` |

## Known limitations

- **Selection bias.** Analysts mostly see REVIEW and BLOCKED transactions, so real labels would cover
  risky cases and contain few approved ones. Fraud the system approved is never labelled unless
  someone reports it (for example through chargebacks), which this project does not model.
- **Synthetic labels in the demo.** See the note at the top.
- **Small evaluation sets.** With a few hundred labels the test slice holds only tens of fraud cases,
  so one transaction can move PR-AUC by several points. The script warns when the test slice has
  fewer than 5 fraud rows.
- **Shared-secret protection only.** The ML service's `/admin/*` routes are protected by a single shared
  token (`ADMIN_TOKEN`), not by per-person accounts, so there is no record of who reloaded a model.
  The label export needs an ADMIN login, but a stolen admin token still exposes the training data.
- **Unpaged export.** `/api/feedback/labels` returns everything in one response. Fine at demo scale;
  a real system would page or stream it.
- **Manual trigger.** Retraining runs when someone runs the script. There is no schedule and no drift
  monitoring yet.

## Tests

| What | How to run |
|---|---|
| Label rules, ordering, stats (Java) | `./mvnw test -Dtest=FeedbackServiceTest` in `backend/` |
| Calibration, registry, dataset builder, split (Python) | `python -m pytest tests/test_feedback_loop.py -v` in `ml-service/` |

See also `docs/decision-policy.md` for how the ML score enters the final decision.
