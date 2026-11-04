# Observability, alerts and failure drills (Phase 10)

> **Honesty note.** FraudGuard runs on **synthetic demo data** on a laptop. The numbers on the dashboard
> describe this demo, not real fraud and not a production system. The alert thresholds are sized for the
> demo and have never been tuned against real traffic.

This page covers what the system exposes (metrics), how to look at it (Grafana), when it complains
(alerts), what to do about each complaint (runbooks), and what actually happened when we broke things on
purpose (drills).

## Starting and stopping the stack

Everything below runs on one machine. Secrets come from `.env` (copy `.env.example` once, the values in the
example are **local demo values only**).

~~~bash
# 0. one-time: make sure .env has every key, and write the Prometheus password file
./scripts/setup-local-env.sh
set -a; source .env; set +a            # load the variables into this shell (repeat in every new terminal)

# 1. databases and Kafka
docker compose up -d postgres redis kafka kafka-ui

# 2. ML service (0.0.0.0 so Prometheus in Docker can reach /metrics; /score and /admin/* need tokens)
cd ml-service && source .venv/bin/activate && python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# 3. backend (Java 21)   -- in another terminal, with .env loaded
cd backend && mvn spring-boot:run

# 4. Prometheus + Grafana
docker compose -f infra/observability/docker-compose.yml up -d

# 5. frontend and demo data
cd frontend && npm run dev             # http://localhost:5173
cd frontend && npm run seed            # in a terminal with .env loaded
~~~

| What | Where |
|---|---|
| Dashboard (React) | http://localhost:5173, log in as `analyst` or `admin` (passwords in your `.env`) |
| Grafana | http://localhost:3000, user `admin`, password from `GRAFANA_ADMIN_PASSWORD` (default `admin`) |
| Prometheus targets and alerts | http://localhost:9090/targets and http://localhost:9090/alerts |
| Kafka UI | http://localhost:8085 |

Stop: `docker compose -f infra/observability/docker-compose.yml down`, `docker compose down`, and Ctrl+C in the
backend and ML terminals. Add `-v` to also delete stored data.

Use `127.0.0.1` (not `localhost`) for the ML service on macOS: `localhost` can resolve to IPv6 and reach a Docker
listener instead.

## Who can see the metrics

`/actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness` are public (status only).
Everything else under `/actuator/**` needs HTTP Basic with the **METRICS** account
(`FRAUDGUARD_METRICS_USERNAME` / `FRAUDGUARD_METRICS_PASSWORD`). Prometheus uses it through
`infra/observability/secrets/metrics_password`, which `scripts/setup-local-env.sh` writes (git-ignored).
Health details (database and Redis status) are shown only to that login.

## Metric catalogue

Every backend metric carries the label `application="fraudguard"`. In code the names use dots
(`fraudguard.decisions`); Prometheus shows underscores and adds `_total` to counters.

### Backend (`/actuator/prometheus`)

| Series | Type | Labels | Meaning |
|---|---|---|---|
| `fraudguard_decisions_total` | counter | `status`, `policy`, `ml` | Final decisions. `status` is APPROVED, REVIEW or BLOCKED; `ml` is `scored` or `rules_only` |
| `fraudguard_reason_codes_total` | counter | `code` | Reason codes attached to decisions |
| `fraudguard_pipeline_latency_seconds_*` | histogram | | Time from API acceptance to the stored decision (includes Kafka retries) |
| `fraudguard_event_processing_seconds_*` | histogram | `outcome` | One Kafka event: `decided`, `duplicate` or `error` |
| `fraudguard_ml_calls_total` | counter | `outcome` | `success`, `failure`, `circuit_open` (skipped), `disabled` |
| `fraudguard_ml_call_seconds_*` | histogram | `outcome` | ML call duration |
| `fraudguard_ml_circuit_open` | gauge | | 1 while the ML circuit breaker is open |
| `fraudguard_dlq_events_total` | counter | `cause` | Events dead-lettered, by exception class. **Appears only after the first one** |
| `fraudguard_outbox_pending` | gauge | | Events saved but not yet published to Kafka |
| `fraudguard_transactions_stuck_pending` | gauge | | Transactions PENDING for longer than `fraudguard.reliability.stuck-after-seconds` (default 120) |
| `fraudguard_redrive_requeued_total` | counter | | Transactions re-queued by the re-drive endpoint |
| `fraudguard_auth_logins_total` | counter | `outcome` | `success`, `failure`, `rate_limited` |
| `http_server_requests_seconds_*` | histogram | `uri`, `status`, `method` | Standard Spring Boot HTTP metrics (bucketed for p95) |
| `hikaricp_connections_active` / `_pending` | gauge | | Database connection pool |
| `kafka_consumer_fetch_manager_records_lag_max` | gauge | `partition`, `topic` | How far the consumer is behind |
| `jvm_memory_used_bytes` | gauge | `area`, `id` | JVM memory |

### ML service (`/metrics`, open)

| Series | Type | Labels | Meaning |
|---|---|---|---|
| `ml_scores_total` | counter | | Transactions scored |
| `ml_score_seconds_*` | histogram | | Time to score one transaction |
| `ml_policy_score_*` | histogram | | The score returned to the backend (policy scale, 0 to 1); its shape shifting is a drift hint |
| `ml_model_reloads_total` | counter | `outcome` | `success` or `failure` of `POST /admin/reload` |
| `ml_model_info` | gauge | `model_version`, `model_type` | 1 for the model being served |

## The Grafana dashboard

`FraudGuard Overview` is provisioned from code (`infra/observability/grafana/dashboards/fraudguard-overview.json`),
so it is there after `docker compose up` with no import. It refreshes every 10 s and shows the last hour.
Edits made in the Grafana UI are not saved back: change the JSON file instead.

| Row | Look here for |
|---|---|
| Overview | Is the backend up, decisions per minute, the decision mix and block rate for the selected range, stuck PENDING (red above 0) |
| Pipeline | p50/p95/p99 latency, events by outcome (an `error` line is a problem), decisions by status, top reason codes |
| ML | ML calls by outcome, ML latency, the circuit (green closed, red open), share of ML-scored decisions, score distribution, the live model |
| Reliability | DLQ events, outbox backlog, HTTP rate and p95 per URI, Kafka lag, Hikari connections, JVM heap |

The block rate and decision mix describe the **synthetic seed traffic**, which is built to contain suspicious
transactions. A high block rate in the demo is expected and says nothing about real fraud.

## Alerts

Rules live in `infra/observability/alerts.yml` and show at http://localhost:9090/alerts. There is **no
Alertmanager**: nothing is sent to email or chat, you have to look. Each alert links to its runbook below.

| Alert | Fires when | For | Severity | First action |
|---|---|---|---|---|
| [BackendDown](#backenddown) | Prometheus cannot scrape the backend | 1m | critical | Is the process running? Check its log |
| [PipelineLatencyHigh](#pipelinelatencyhigh) | p95 pipeline latency above 5 s | 5m | warning | Check Kafka lag and ML latency panels |
| [DlqEventsDetected](#dlqeventsdetected) | any event dead-lettered in 10 min (critical above 10) | 0m | warning / critical | `GET /api/admin/dlq/summary`, read the cause |
| [ConsumerErrors](#consumererrors) | consumer `error` outcomes | 5m | warning | Read the backend log for the exception |
| [OutboxBacklog](#outboxbacklog) | more than 100 unpublished events | 5m | warning | Is Kafka up? |
| [TransactionsStuckPending](#transactionsstuckpending) | any transaction PENDING over 120 s | 3m | critical | Fix the cause, then call the re-drive endpoint |
| [MlCircuitOpen](#mlcircuitopen) | ML circuit open | 2m | warning | Is the ML service up and responsive? |
| [MlFailureRatioHigh](#mlfailureratiohigh) | over 20% of ML calls fail | 5m | warning | Same as above; check ML service logs |
| [BlockRateAnomalous](#blockrateanomalous) | over 30% of decisions BLOCKED for 15 min | 15m | info | Is this a seed run, or did a rule or model change? |
| [Http5xxRate](#http5xxrate) | over 5% of requests are 5xx | 5m | warning | Check database and Kafka, then the log |
| [HikariSaturation](#hikarisaturation) | requests waiting for a DB connection | 2m | warning | Is PostgreSQL slow or down? |

### Checking the alert rules

~~~bash
P="docker run --rm -v $PWD/infra/observability:/w -w /w --entrypoint promtool prom/prometheus:v2.55.1"
$P check rules alerts.yml
$P test rules alerts_test.yml          # unit tests: BackendDown, DlqEventsDetected, MlCircuitOpen, TransactionsStuckPending
jq empty infra/observability/grafana/dashboards/fraudguard-overview.json && echo dashboard JSON ok
~~~

(`promtool check config prometheus.yml` only passes inside the Prometheus container, where `alerts.yml` is mounted
at `/etc/prometheus`.)

## Runbooks

### BackendDown
Prometheus cannot reach `host.docker.internal:8080`, or gets a 401 from it.
1. `lsof -nP -iTCP:8080 -sTCP:LISTEN`: is the backend running? If not, start it (see above) and read its log.
2. If it runs but the target shows 401 at http://localhost:9090/targets, the METRICS password differs from
   `infra/observability/secrets/metrics_password`. Re-run `./scripts/setup-local-env.sh` and recreate Prometheus.

### PipelineLatencyHigh
Transactions are being decided, but slowly. Look at the **Kafka consumer lag** panel (consumer behind), the **ML
latency** panel (slow scoring), and **Hikari connections** (database waits). Kafka retries after an outage also
show up here, because the clock starts when the API accepted the transaction.

### DlqEventsDetected
Events failed all retries (or were malformed) and were copied to `transactions.dlq`.
1. `GET /api/admin/dlq/summary` (ADMIN login) shows how many records the DLQ holds and the exception per recent record.
2. `IllegalArgumentException` means a malformed or unsupported message (never retried; fix the producer).
   Anything else (database or Redis errors) means a dependency was down longer than about 7 s.
3. In the second case the affected transactions are still PENDING: see [TransactionsStuckPending](#transactionsstuckpending).

### ConsumerErrors
The consumer is throwing. Each error is retried (1, 2, 4 s) and then dead-lettered. Read the backend log around
the first `ERROR`, usually a dependency (Redis, PostgreSQL) that is down.

### OutboxBacklog
Transactions are saved but not reaching Kafka. The API still answers 202. Start Kafka (`docker compose start kafka`);
the relay publishes the backlog in order, oldest first, and the gauge returns to 0. Nothing is lost.

### TransactionsStuckPending
Transactions stayed PENDING past 120 s: their event was dead-lettered while a dependency was down.
1. Make sure the dependency is back (Redis, PostgreSQL, Kafka), `readiness` returns 200.
2. Re-queue them: `POST /api/admin/transactions/redrive-pending?olderThanSeconds=120&limit=500` with an ADMIN token.
   It writes a **new** outbox event for each stuck transaction, so the normal pipeline decides them. It is safe to
   call repeatedly: decided transactions are no longer PENDING, and ones with an unsent event are skipped.
3. The gauge should fall to 0 within a minute.

### MlCircuitOpen
After 3 failed ML calls in a row the backend stops calling the ML service for 30 s and decides with rules only
(reason code `ML_UNAVAILABLE`). Check `curl http://127.0.0.1:8000/health`. If the service is up but frozen, restart
it. One successful trial call after the 30 s closes the circuit by itself.

### MlFailureRatioHigh
More than a fifth of ML calls fail (timeouts at 1.5 s count). Check the ML service log and its CPU. If it started
failing after a promotion, roll back the model (see `docs/feedback-loop.md`).

### BlockRateAnomalous
More than 30% of decisions were BLOCKED. Informational: right after `npm run seed` this is normal. Otherwise check
whether a rule threshold or the policy changed, and whether one account or device dominates the **top reason codes**.

### Http5xxRate
The API is returning server errors. Check **Hikari connections** and the readiness endpoint (database), then the
backend log for the stack trace.

### HikariSaturation
Requests are waiting for a database connection. Usually PostgreSQL is down or slow (drill 5). If the database is
healthy, a slow query is holding connections.

## Failure drills

Scripts are in `scripts/drills/`, one per drill. Each prints what it will do and waits for Enter before changing
anything (set `DRILL_YES=1` to skip the waiting). Run them with `.env` loaded and the whole stack up:
`./scripts/drills/01-ml-service-down.sh`. Results below were recorded on 2026-11-04 on a MacBook with Docker Desktop.

| # | Drill | Observed (2026-11-04) | Matches the plan? |
|---|---|---|---|
| 1 | [ML service down](../scripts/drills/01-ml-service-down.sh) | API kept returning 202. Decisions were `APPROVED` with reason code `ML_UNAVAILABLE`. After 3 failures `fraudguard_ml_circuit_open` went to 1 and further calls were counted `circuit_open`. After restarting the service and waiting out the 30 s window, the next call succeeded and the circuit read 0. | Yes |
| 2 | [ML service hangs](../scripts/drills/02-ml-service-hangs.sh) (`kill -STOP` / `-CONT`) | Calls timed out at about 1.5 s (`fraudguard_ml_call_seconds_max{outcome="failure"}` = 1.506 s), the circuit opened, and after `kill -CONT` plus 30 s it closed again. | Yes |
| 3 | [Redis down](../scripts/drills/03-redis-down.sh) | Ingestion is unaffected (all 202): the API does not use Redis, and there is no `RateLimitFilter` in this codebase. Readiness went to 503. The consumer failed each event 4 times, then dead-lettered it: `fraudguard_dlq_events_total{cause="RedisConnectionFailureException"}` = 5 and `error` outcomes = 20 for 5 events. The 5 transactions stayed PENDING. After Redis returned, `redrive-pending` re-queued 5 and PENDING fell to 0. | **Only after a fix.** First run, with Spring's default 60 s Redis timeout, nothing was dead-lettered after 20 s because each attempt blocked for up to a minute. Now `spring.data.redis.timeout` and `connect-timeout` are 2 s. |
| 4 | [Kafka down](../scripts/drills/04-kafka-down.sh) | API still returned 202 for all 8. `fraudguard_outbox_pending` showed 8. Within the first 5 s poll after Kafka restarted, the outbox read 0 and no transaction was left PENDING. | Yes |
| 5 | [PostgreSQL down](../scripts/drills/05-postgres-down.sh) | List and ingest calls returned 500, readiness 503, `hikaricp_connections_pending` = 6. After restart readiness returned 200 and the API worked again without restarting the backend. | Yes (errors are 500, not a friendlier 503) |
| 6 | [Poison message](../scripts/drills/06-poison-message.sh) | Went straight to `transactions.dlq` (no retries). The DLQ summary showed `java.lang.IllegalArgumentException`, and the counter `{cause="IllegalArgumentException"}` = 1. | Yes |
| 7 | [Duplicate event](../scripts/drills/07-duplicate-event.sh) | Republishing the exact outbox event gave `fraudguard_event_processing_seconds_count{outcome="duplicate"}` = 1 and the status was unchanged. | Yes |
| 8 | [Backend restart under load](../scripts/drills/08-backend-restart-under-load.sh) | Backend stopped with SIGTERM during `npm run seed`, then started again. After it was back, PENDING = 0 and the outbox backlog = 0, with no re-drive needed. | Yes. One run only, and the seed script itself tolerates connection errors; this is not a statistical test. |
| 9 | [Corrupt registry](../scripts/drills/09-corrupt-registry.sh) | `POST /admin/reload` returned 409, the old model (`gbm-20261030-052117`) kept serving, and `ml_model_reloads_total{outcome="failure"}` = 1. The script restores the file. | Yes |

What the drills do **not** show: the `TransactionsStuckPending` alert firing (it needs the transactions to stay stuck for 120 s plus 3 min; the
drill re-drives sooner), and the other alerts firing in real time. Those are covered only by the `promtool` unit tests.

Findings that changed the code: the Redis timeout above (drill 3); the helper scripts needed to cache their login because the
login endpoint allows 5 attempts a minute; `localhost` vs `127.0.0.1` for the ML service (see the start-up section).

## Limitations

- **Synthetic data, demo thresholds.** See the note at the top. Alert limits were chosen by reasoning, not tuned on real traffic.
- **No notifications.** Without Alertmanager an alert is visible only if someone opens the Prometheus UI.
- **One instance.** The login rate limit and the ML circuit breaker are per backend instance and kept in memory.
- **Login rate limit uses the connection address.** Behind a reverse proxy every user would share the proxy's
  address; a real deployment needs the forwarded client address and a shared store such as Redis.
- **Demo logins on the sign-in page.** For quick demos the page lists the demo usernames and passwords, compiled into the frontend. Anyone who can open the page can read them. Turn them off with `SHOW_DEMO_LOGINS=false` and rebuild the frontend for anything shared.
- **Token storage.** The dashboard keeps the login token in `sessionStorage`. Any script running on the page could
  read it, so an XSS bug would expose it. The alternative (an httpOnly cookie) trades that for needing CSRF protection.
- **Shared secrets, not per-service identities.** The ML service and Prometheus authenticate with shared secrets from `.env`.
- **Re-drive is manual.** Stuck transactions are alerted on and recoverable, but not recovered automatically.
- **Kafka lag panel** relies on the consumer client metrics; it shows `max` per partition, not per consumer group.
- **Local Docker only.** Nothing here has been run on separate machines or with real network failures.
