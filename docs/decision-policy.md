# Decision policy (policy-v1)

Every processed transaction gets one decision: `APPROVED`, `REVIEW` or `BLOCKED`, with reason codes,
a plain-English explanation, and a JSON snapshot of every input (`transactions.decision_inputs`).

## Inputs
1. **Rule verdict**: fraud rules over the Redis velocity features give a score from 0 to 100.
   Score >= 40 means the rules say REVIEW; score >= 80 means the rules say BLOCKED.
2. **ML anomaly score** (0-1, Isolation Forest): a *ranking* score of how unusual the transaction
   looks. It is **not a calibrated probability of fraud**.
3. **Velocity features**: used by the rules and the model, and also by the policy itself: an
   account with fewer than 5 earlier transactions has no baseline, so its ML score is ignored.

## Policy
| Rules say | ML normal / unavailable / ignored | ML elevated (>= 0.66) | ML very high (>= 0.70) |
|---|---|---|---|
| APPROVED | APPROVED | REVIEW | REVIEW |
| REVIEW | REVIEW | REVIEW | **BLOCKED** |
| BLOCKED | BLOCKED | BLOCKED | BLOCKED |

- ML alone can send a clean transaction to review but **never blocks**.
- If the ML service is unavailable (down, slow, disabled), the rules decide alone and the decision
  records `ML_UNAVAILABLE`.

## Reason codes
Rule codes (`HIGH_VELOCITY`, `UNUSUAL_AMOUNT`, `NEW_DEVICE_HIGH_AMOUNT`, `RAPID_COUNTRY_CHANGE`), then
`ML_ANOMALY_ELEVATED`, `ML_ANOMALY_VERY_HIGH`, `ML_UNAVAILABLE`, `ML_IGNORED_NEW_ACCOUNT`, and
`RULES_AND_ML_AGREE` when rules and ML together caused a block. `NO_RISK_SIGNALS` when nothing fired.

## Where the ML thresholds come from
Chosen from the training report (`ml-service/models/isoforest-v1.metrics.json`) on the *validation*
split: 0.66 is the best-F1 threshold (precision 0.82, recall 0.81); 0.70 is where precision passed
90% (0.93, recall 0.36). They are in `application.yml` under `fraudguard.decision`.

**Caveat:** the model is trained and evaluated on synthetic data. These thresholds demonstrate the
method; they are not evidence of real-world fraud-detection performance.

## Changing the policy
Edit `fraudguard.decision` (or the rule numbers under `fraudguard.rules`), bump `policy-version`, and
restart. Old decisions keep the policy version and thresholds they were made with.