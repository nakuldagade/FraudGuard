# ADR 0004: Redis for velocity features

Status: accepted. Date: 2026-11-04.

## Context
Good fraud signals depend on recent behaviour: how many transactions in the last 5 minutes and hour, how the amount compares with the
account's usual amount, whether the device, merchant or country is new. Computing that from the database for every event would be slow and
would load the system of record.

## Decision
Keep per-account recent state in Redis and update and read it in one atomic Lua script, `record_transaction.lua`, so concurrent events for one account
cannot interleave. The training code in the ML service mirrors the same feature definitions, so training data and live data are computed the same way.

## Consequences
- Fast reads and writes next to the consumer, and the features are available both to the rules and to the ML model.
- Redis becomes a dependency of the pipeline: if it is down, events are retried and then dead-lettered (see [ADR 0003](0003-retries-and-dlq.md)). Readiness reports it.
- The ingestion API does not use Redis, so intake keeps working during a Redis outage.
- Features exist only as long as Redis keeps them; the full inputs of each decision are also stored in `decision_inputs` so a decision can be explained and used for training later.

## Alternatives considered
- **Compute from PostgreSQL.** One less component, but slower under load and heavier on the database.
- **A stream-processing framework (Kafka Streams or Flink).** Powerful windows, much more to learn and run for a demo.
- **In-process memory.** Fastest, but lost on restart and wrong with more than one backend instance.
