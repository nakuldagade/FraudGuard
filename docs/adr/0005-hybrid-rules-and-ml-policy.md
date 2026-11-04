# ADR 0005: Hybrid rules and ML decision policy

Status: accepted. Date: 2026-11-04.

## Context
Rules are explainable and predictable but only catch what someone wrote down. An anomaly model can flag the unusual but gives a score nobody can
explain, and the score is a ranking, not a probability of fraud.

## Decision
Both run, and an explicit, versioned policy (`policy-v1`) combines them: start from the rule verdict; an elevated ML score (at or above 0.66) sends a
clean transaction to `REVIEW`; a very high one (0.70) turns a rule `REVIEW` into `BLOCKED`. **ML alone never blocks.** For accounts with fewer than 5
earlier transactions the ML score is ignored. Every decision stores the policy version, reason codes, a plain-English explanation and a snapshot of its inputs.

## Consequences
- Every decision can be explained and reproduced, and changing a threshold is a config change plus a version bump (see [decision-policy.md](../decision-policy.md)).
- The full table of 15 rule x ML combinations is enforced by a unit test.
- The thresholds are numbers chosen for the demo's synthetic data. They are not validated against real fraud.
- Two systems to keep consistent: a promoted ML model is calibrated onto the policy's score scale ([ADR 0007](0007-feedback-loop-promotion-gate-calibration.md)).

## Alternatives considered
- **Rules only.** Explainable but blind to new patterns.
- **ML only.** Catches more, but unexplainable decisions are hard to defend to an analyst or customer.
- **Let ML block on its own.** Rejected: with a ranking score and no real-world validation, a block needs a second signal.
