# Changelog

All notable changes. FraudGuard runs on synthetic demo data and is not production-grade.

## [1.0.0] - Unreleased

First complete version. Phases 12 and 13 (below) were added on top of Phases 1 to 11; see the sections after this one for the details of
Phases 10 and 11 and `git log` for the earlier phases.

### Added
- **Docker.** Multi-stage, non-root images for the backend (layered jar), ML service (trains `isoforest-v1` at build time) and frontend (nginx
  proxying `/api`); `docker compose --profile app up --build` starts the whole application with health checks and ordered start-up.
- **CI and security automation.** GitHub Actions for build and test, CodeQL, Trivy image scans, `pip-audit`, `npm audit`, gitleaks, Dependabot, and a
  tag-triggered release to GHCR (written and linted, not yet run on GitHub).
- **Developer experience.** `Makefile` (`up`, `down`, `seed`, `test`, `lint`, `demo`, `openapi`), PR template, `scripts/release-notes.sh`.
- **API documentation.** springdoc OpenAPI and Swagger UI, `docs/openapi.json`, `docs/api-examples.http`.
- **Documentation.** New README, rewritten architecture doc with diagrams, nine ADRs, demo script, portfolio material, publish checklist, screenshots.
- MIT licence.

### Changed
- ML model files, the registry and feedback exports are no longer tracked in git (the Docker build and `python training/train.py` create `isoforest-v1`).
- Frontend port in Docker is 8081 (3000 is Grafana). `FRONTEND_PORT` in `.env.example` changed to match.
- ML dependencies updated: FastAPI 0.162 and Starlette 1.10 (older Starlette had known advisories), pytest 9.2.

### Fixed
- nginx forwarded `Host` without the port, so browsers' login requests were rejected as cross-origin (403).
- The Grafana "Block rate" panel showed "No data" instead of 0% when nothing had been blocked.
- npm audit high-severity finding (`zod`) resolved.


## Unreleased: Phase 11 (testing and performance)

### Added
- **Coverage gates.** JaCoCo in Maven (`mvn verify` fails below 70% overall or 85% in `rules` and `decision`; measured 94.6%),
  `pytest-cov` for the ML service (85% of `app/`; measured 95%), Vitest coverage for the frontend (93.5%).
- **Tests.** Redis-outage integration test (Testcontainers pause, then dead-letter, then re-drive), Java and Python contract tests
  against `contracts/ml-score.schema.json`, 22 frontend component tests (Vitest, Testing Library, MSW), a Playwright smoke test.
- **Performance.** k6 load tests in `perf/` (ingestion, end to end, read path on 100k rows, 30-minute soak), a 100k-row seed script,
  `docs/performance.md` and `docs/testing.md`.

### Changed
- **Outbox relay is batched** (500 per round, async sends, 200 ms poll). End-to-end p95 at 50 req/s went from 12.8 s to 0.23 s.
- Kafka listener concurrency set to 3 (one thread per partition).
- `V9__status_review_index.sql`: composite `(status, review_state)` index replaces the `status` index.
- The summary endpoint uses one grouped query instead of about 11 counts.

## Phase 10 (reliability, observability, security)

### Added
- **Reliability.** Gauge `fraudguard.transactions.stuck.pending`; `POST /api/admin/transactions/redrive-pending`
  re-queues stuck PENDING transactions with a new outbox event; `GET /api/admin/dlq/summary` shows DLQ size and the
  exception per recent record; graceful shutdown (`server.shutdown: graceful`, 20 s).
- **Observability.** Grafana dashboard as code (`FraudGuard Overview`), 12 Prometheus alert rules with `promtool`
  unit tests, ML service `/metrics` (scores, latency, score distribution, reloads, live model), Prometheus scraping
  both services, HTTP latency histograms.
- **Failure drills.** Nine scripts in `scripts/drills/` and recorded results in `docs/observability.md`.
- **Security.** Login with BCrypt users and roles ANALYST/ADMIN (`V8__users.sql`), stateless JWT (HS256), login rate
  limit (5 per minute per IP), service API key for ingestion, HTTP Basic for `/actuator/**`, ADMIN-only label export
  and admin routes, token checks on the ML service (`X-Service-Token`, `X-Admin-Token`), login page in the dashboard.
- `scripts/setup-local-env.sh` and a complete `.env.example`.

### Changed
- The reviewer on an analyst review is now the logged-in user. `reviewer` in the request body is optional and ignored.
- `npm run seed` needs `INGEST_API_KEY`, `SEED_USERNAME`, `SEED_PASSWORD` (see `.env.example`); its reviews are
  attributed to the seed user `demo-oracle`.
- `retrain.py` logs in as the admin user to download labels; the reload hint includes the admin token.
- `/actuator/health` shows details only to the METRICS login.
- `SPRING_PROFILES_ACTIVE` in `.env.example` is now `local` (it said `dev`, which has no datasource settings).

### Fixed
- ML API tests pinned to `isoforest-v1` (they broke once a promoted model became active) and no longer import a removed constant.
