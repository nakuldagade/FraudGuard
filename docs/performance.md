# Performance (Phase 11)

> **Honesty note.** These numbers come from one laptop running everything at once (backend, PostgreSQL, Kafka,
> Redis, the ML service, Prometheus, Grafana, Docker) with **synthetic traffic**. They show where this design
> bottlenecks and whether the tuning helped. They are not capacity figures for production hardware, and a
> different machine will give different numbers.

## Setup

| | |
|---|---|
| Machine | Apple M4, 10 cores, 16 GB RAM, macOS 27; Docker Desktop given 10 CPUs and about 8 GB |
| Stack | Spring Boot backend on the host (JDK 21, default JVM settings), PostgreSQL 16 / Redis 7 / Kafka 3.8 (single broker) in Docker, ML service (gradient boosting model) on the host, Prometheus and Grafana in Docker |
| Load generator | k6 in Docker on the same machine, so it competes for the same CPUs |
| Data | Ingestion tests create new transactions with unique idempotency keys. The read test ran on **100,000 extra synthetic rows** (`perf/seed-100k.sql`, random data) on top of about 2,500 demo rows, growing to about 147,000 as the load tests added rows |
| Date | 2026-11-04 |

Run it yourself: `./perf/run.sh ingest|e2e|read|soak` (k6 runs from its Docker image; results land in `perf/results/`).
Seed the big table with `docker compose exec -T postgres psql -U fraud_user -d fraud -v rows=100000 < perf/seed-100k.sql`
and remove it afterwards with `perf/cleanup.sql`.

## Results

| Test | Target | Before tuning | After tuning |
|---|---|---|---|
| (a) Ingestion: ramp to 50 req/s, hold 4 min, unique keys | p95 < 300 ms, errors < 1% | p95 12.8 ms, 0.00% errors | p95 12.1 ms, 0.00% errors |
| (b) End to end at 50 req/s: p95 of `fraudguard_pipeline_latency_seconds` | < 2 s | **p95 12.8 s** (p50 6.5 s). Missed | **p95 0.23 s** (p50 0.15 s). Met |
| (b) Peak outbox backlog during the run | | 645 events | 11 events |
| (c) Read path: list and filter queries, 20 req/s for 2 min, 100k+ rows | p95 < 200 ms | p95 107 ms overall (summary 129 ms, list 23 ms, filter 26 ms, by account 11 ms) | p95 35 to 58 ms overall (see the note below); every query kind under 65 ms |
| (d) Soak: 10 req/s for 30 min | no heap growth trend | not run before tuning | **Met**: 18,001 requests, 0.00% errors, p95 13.4 ms; heap low points 49 to 62 MB with no upward trend; no DLQ events, outbox backlog at most 2 |

The ingestion rate averaged 45 req/s over the 5 minutes because the first minute is the ramp; the hold phase ran at 50.
Hikari never had a waiting request (`hikaricp_connections_pending` stayed 0), and Kafka consumer lag stayed 0.

**Note on the read numbers.** The table kept growing between runs (about 102,000 rows before, about 147,000 after, because the
ingestion tests added about 45,000), and the first run after the restart was on a cold JVM. So the overall read figures are not a
clean comparison. The cleaner evidence is the query plans below.

## Where the time went (bottleneck analysis)

1. **The outbox relay was the end-to-end bottleneck.** It published at most 50 events per round, sent them one at a time (waiting for
   each Kafka confirmation), then slept 1 second. That caps it near 50 events/s even before any waiting, so at 50 req/s the
   backlog grew to 645 events and the p95 delay from "accepted" to "decided" was 12.8 s, while the API itself stayed fast (12.8 ms)
   and Kafka lag stayed 0. The API accepting a request quickly says nothing about when it is decided.
2. **Page totals and the summary were database-bound.** The dashboard asks "how many REVIEW and still UNREVIEWED?" With only a
   `status` index, PostgreSQL read every REVIEW row and filtered it. The summary endpoint ran about 11 separate COUNT queries.
3. **Everything else was fine.** The account filter already used `idx_transactions_account_occurred` (0.3 ms), and the unfiltered,
   status, risk-range and date queries were index scans of 0.2 to 4 ms.

## What was changed

| Change | Why |
|---|---|
| Outbox relay: batch of 500 (was 50), sends handed to Kafka together and confirmed afterwards, polled every 200 ms (was 1 s) | Removes the 50 events/s ceiling. On a failure the events before it are marked published and the rest retry; events after the failure may be delivered twice, and the consumer ignores a repeated event id |
| `spring.kafka.listener.concurrency: 3` | One consumer thread per partition of `transactions.raw` (3). Events of one account still go to one partition, so their order is kept |
| `V9__status_review_index.sql`: index on `(status, review_state)`, drop the old `status` index | Page totals and summary counts come from an index-only scan |
| Summary endpoint: one grouped query instead of about 11 counts | Summary p95 129 ms before, about 35 to 39 ms after |
| Left alone: Hikari pool size (default 10) | It never saturated at 50 req/s |

## Query plans (`EXPLAIN ANALYZE`, PostgreSQL 16)

| Query | Result |
|---|---|
| Newest page, no filter | index scan on `occurred_at`, 0.5 ms |
| REVIEW + UNREVIEWED, newest first, 20 rows | index scan on `occurred_at` with a filter, 4 ms |
| Account filter | `idx_transactions_account_occurred`, 0.3 ms |
| Risk range | index scan, 0.2 ms |
| Page total for REVIEW + UNREVIEWED (102k rows, old `status` index) | 27 ms |
| The same page total, 147k rows, **no** composite index (measured by dropping it inside a rolled-back transaction) | 95 ms (sequential scan) |
| The same page total, 147k rows, **with** `idx_transactions_status_review` | 1.4 ms (index-only scan) |
| Summary grouped query, 147k rows | 20 ms with the index, 26 ms without |

The total for a page is computed with a `count(*)` on every request, which grows with the table. At millions of rows that count
would need keyset paging or an approximate total.

## Caveats

- One machine, one run per test (one 30-minute soak). No confidence intervals; differences of a few milliseconds are noise.
- The load generator shares the CPUs with the system under test, which makes the numbers pessimistic for the server and
  unrepresentative of a separate load generator.
- Synthetic, uniform data: random accounts, a fixed status mix. Real data skews (hot accounts, bursts) and would behave differently.
- The ML service is a small model on the same machine; a bigger model or a remote service would change ML latency and the circuit
  behaviour.
- Kafka is a single broker without replication, so these numbers say nothing about a real cluster.
- The soak shows no heap growth over 30 minutes at 10 req/s. It does not rule out slower leaks, and nothing here was run for
  hours.
- The alert thresholds and dashboards were not re-tuned from these results.
