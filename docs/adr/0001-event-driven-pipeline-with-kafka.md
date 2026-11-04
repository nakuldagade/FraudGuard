# ADR 0001: Event-driven pipeline with Kafka

Status: accepted. Date: 2026-11-04.

## Context
A payment must be acknowledged fast, but deciding on it involves slower steps that can fail on their own: Redis lookups, an ML call, rules,
a database write. If the API did all of that before answering, the caller would wait for the slowest step and a failure in any of them
would fail the payment.

## Decision
The API stores the transaction as `PENDING`, answers `202 Accepted`, and publishes an event to Kafka (`transactions.raw`). A consumer in
the backend does the enrichment, scoring and decision afterwards and updates the status. The message key is the `accountId`, so one
account's events go to one partition and are processed in order, which the velocity features rely on.

## Consequences
- Intake stays fast and independent of the ML service and Redis (measured: ingestion p95 about 12 ms at 50 req/s, see [performance.md](../performance.md)).
- The result is eventually consistent: a client polls `GET /api/transactions/{id}`. A decision is normally ready in well under a second (p95 0.23 s at 50 req/s on a laptop).
- Kafka delivers at least once, so the consumer must ignore repeats ([ADR 0002](0002-transactional-outbox.md)).
- More moving parts to run and monitor than a synchronous call.
- Only `transactions.raw` and `transactions.dlq` are used. Two more topics (`scored`, `decisions`) were planned for separate stages and are created but unused, because one consumer proved enough.

## Alternatives considered
- **Synchronous scoring inside the request.** Simplest, but every dependency becomes part of the request's latency and failure.
- **A database queue (polling a table).** Fewer components, but no partitioned parallelism or replay, and the table becomes the hot spot.
- **Separate microservices per stage with a topic between each.** Fits larger teams; here it would add operational cost without a demonstrated need.
