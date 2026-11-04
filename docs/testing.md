# Testing (Phase 11)

> **Honesty note.** All tests use synthetic data and fakes. Passing them shows the code does what the tests say,
> not that the system detects real fraud. Coverage numbers measure which lines ran, not how good the tests are.

## What is tested, and how to run it

| Part | Command | What it covers |
|---|---|---|
| Backend | `cd backend && mvn verify` (Java 21, Docker running) | Unit tests, Spring + Testcontainers integration tests (Postgres, Kafka, Redis), security matrix, JaCoCo coverage gate |
| ML service | `cd ml-service && source .venv/bin/activate && pytest` | API, features, training, feedback loop, token checks, metrics, contract. Fails below 85% coverage of `app/` |
| Frontend (unit and component) | `cd frontend && npm test` (`npm run test:coverage` for coverage) | Vitest + Testing Library + MSW (a fake API, no backend needed) |
| Frontend (end to end) | `cd frontend && npm run test:e2e` | Playwright against the **real running stack**; needs seeded data and `.env` loaded |
| Alert rules | `promtool` via Docker, see [observability.md](observability.md#checking-the-alert-rules) | Rule syntax and three alert unit tests |
| Performance | `./perf/run.sh ingest\|e2e\|read\|soak` | k6 load tests, see [performance.md](performance.md) |

Java 21 is required: with newer JDKs Lombok fails to compile (`java -version` first; on macOS
`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`).

## Coverage (measured 2026-11-04)

| Part | Line coverage | Gate |
|---|---|---|
| Backend (JaCoCo) | 94.6% | `mvn verify` fails below 70% overall, or below 85% in `com.fraudguard.rules` and `com.fraudguard.decision` |
| ML service `app/` (pytest-cov) | 95% | `pytest` fails below 85% |
| ML service `training/` | 42% to 84% per script | not gated: these are command-line scripts, only their core logic is tested |
| Frontend (Vitest, v8) | 93.5% | not gated yet |

The backend report is `backend/target/site/jacoco/index.html` after `mvn verify`.

## Backend test layers

- **Unit (no Spring, no Docker):** `RuleEngineTest`, `DecisionEngineTest`, `MlServiceClientTest`, `MlServiceClientMetricsTest`,
  `TransactionEventConsumerMetricsTest`, `OutboxRelayTest`, `StuckPendingMetricsTest`, `MlContractTest`, `SecurityUnitTest`.
- **Integration (Testcontainers: PostgreSQL, Kafka, Redis):** `TransactionControllerTest` (POST then decided, idempotent replay),
  `TransactionEventConsumerTest` (poison message to the DLQ, retries, duplicate event), `TransactionReviewApiTest`,
  `FraudDecisionTest`, `MlScoringIntegrationTest`, `ReliabilityIntegrationTest` (re-drive, DLQ summary),
  `RedisOutageIntegrationTest` (Redis frozen with Docker pause, then retry, dead-letter and re-drive recovery), `SecurityApiTest`.
- **The ML service is faked** with a small HTTP server built into the JDK (`MlServiceClientTest`): success, slow response (timeout),
  server error, invalid body, unreachable, and the circuit breaker opening and closing. No WireMock is needed.
- **Decision policy matrix:** `DecisionEngineTest.policyTable` runs every rule verdict x ML signal combination (15) and
  the score boundaries; the table is printed in [decision-policy.md](decision-policy.md).

## Contract between Java and Python

`contracts/ml-score.schema.json` is the single agreed shape of `POST /score`. `MlContractTest` (Java) checks that the serialised
`MlScoreRequest` validates against it and that the example response parses into `MlScoreResponse`. `tests/test_contract.py` (Python)
checks that `ScoreRequest` has exactly the contract's fields and that what the service returns validates. Renaming a field on one
side without the other fails a test. Known gap: Pydantic accepts `"yes"` for a boolean, which the contract forbids; the Java
side always sends real booleans.

## Frontend tests

Component tests (`src/test/*.test.tsx`) render the real app behind the real login gate and answer `fetch` with MSW:
login success, wrong password, rate limit, bearer token on requests, 401 sending the user back to login, logout, one-click demo accounts, the light/dark theme, the details drawer (opens on click, closes with the button and Esc), the KPI cards and charts, table
rendering and errors, the status filter and account search reaching the backend, paging, opening the detail, submitting a
review **without** a reviewer name, and the Model feedback panel (labels, ML service down, registry mismatch, promotions).
The Playwright smoke test (`e2e/smoke.e2e.ts`) does one real pass: wrong password, login, filter, open a row, submit a review.
It logs in twice, and the login endpoint allows 5 attempts a minute per IP, so wait a minute between runs.

## Known gaps

- No browser tests beyond the one smoke test; no accessibility or visual tests.
- Kafka and Redis outages are tested on single containers; there is no multi-broker or network-partition test.
- Load tests run on a laptop (see [performance.md](performance.md)).
- The frontend has no coverage gate.
