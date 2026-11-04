# ADR 0006: Optional ML with a circuit breaker

Status: accepted. Date: 2026-11-04.

## Context
The ML service is a separate process that can be down, slow or broken. If decisions depended on it, an ML outage would stop all decisions or make the
consumer wait on every call.

## Decision
Scoring is optional. The backend calls `POST /score` with a 1.5 s timeout. After 3 failures in a row the circuit opens for 30 s and calls are skipped;
then one trial call decides whether to close it. Whenever there is no score the decision uses the rules alone and records the reason code `ML_UNAVAILABLE`.

## Consequences
- Decisions keep flowing during an ML outage or freeze (drills 1 and 2). Rules-only decisions are visible: `fraudguard_decisions_total{ml="rules_only"}`, the circuit gauge and `MlCircuitOpen`.
- During an outage fewer risky transactions are caught (ML is the part that sends clean-looking ones to review), so an ML outage is a quality drop, not a stop.
- A transaction decided without ML is not re-scored later.
- The breaker state is in memory, per backend instance.

## Alternatives considered
- **Required ML (fail the event).** Retries and dead-letters during every outage.
- **A library (Resilience4j).** Fine, but the breaker needed here is small and easy to test with an injectable clock.
- **Queue transactions for re-scoring when ML returns.** More complete, more complex; not needed for the demo.
