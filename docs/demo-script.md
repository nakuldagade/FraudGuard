# Demo script (about 5 minutes)

> The demo runs on **synthetic data**. Say so at the start: the scores, the block rate and the model improvement show how the
> system works, not how well it would catch real fraud.

**Before you start** (once, about 3 minutes, so the numbers are warm): `make demo`. That starts everything, sends about 200 demo
transactions, labels them, retrains, promotes the new model and loads it. Then open these tabs:

| Tab | URL | Login |
|---|---|---|
| Dashboard | http://localhost:8081 | no typing needed: click **Sign in as Analyst** (or **Admin**) on the login page, which lists the demo credentials |
| Grafana | http://localhost:3000 → *FraudGuard Overview* | `admin` / `GRAFANA_ADMIN_PASSWORD` |
| Prometheus alerts | http://localhost:9090/alerts | none |
| API docs | http://localhost:8080/swagger-ui/index.html | none |

Have one terminal ready in the project folder with `.env` loaded (`set -a; source .env; set +a`).

## 0. The first impression (20 s)
Open the dashboard: the sign-in page shows both demo accounts with their credentials. Click **Sign in as Analyst**. Point at the KPI cards (numbers count up), the **Activity** chart (transactions per hour, hover a column), the **Decisions** donut and the **Analyst verdicts** bars. Use the moon icon to switch to the dark theme.

## 1. The normal flow (60 s)
*Say:* "A payment arrives, is acknowledged at once, and is decided in the background by rules plus an ML score."
1. Dashboard, filter **Decision = Review**. Click the top transaction: the details slide in from the right (Esc or the X closes them).
2. Point at the **explanation** ("Rules scored 40/100 (HIGH_VELOCITY)... ML anomaly score ... in the normal range"), the **rule hits**, the **ML score and model**, and **Decision inputs**. "Every decision keeps the numbers it was based on."
3. "ML alone can send a clean transaction to review but never blocks. See `docs/decision-policy.md`."

## 2. Scenarios: spike, burst, country hop (60 s)
The seed script creates labelled scenarios. Search by account to show each (the account id contains the scenario name):
- `acc-amount-spike-...`: a payment far above the account's usual amount.
- `acc-velocity-burst-...`: many transactions in 5 minutes (`HIGH_VELOCITY`).
- `acc-country-hop-...`: country changes within an hour (`RAPID_COUNTRY_CHANGE`).
Live version: in the terminal run `cd frontend && npm run seed`, watch the counters at the top move and the Grafana **Decisions/min** panel rise.

## 3. Analyst review (30 s)
Open a REVIEW transaction, type a note, click **Request further review**. "The reviewer is the logged-in user, taken from the token, not typed in. The model's decision never changes, the verdict is stored separately."

## 4. Label, retrain, promote (60 s)
Scroll to **Model feedback**: label counts, the live model, the promotion history.
"Analyst verdicts become training data. `make demo` already ran the retrain. The new model is only promoted if it passes a gate: enough labels, enough of each class, and a PR-AUC gain of at least 0.02. The labels here are synthetic, so the gain describes the generator's patterns, not real fraud."
To re-run it live (about a minute): `make demo` again, or see `docs/feedback-loop.md`.

## 5. Grafana (45 s)
Dashboard *FraudGuard Overview*: decisions per minute, pipeline latency p50/p95/p99, reason codes, ML calls, the circuit state, outbox backlog, Kafka lag. "It is provisioned from a JSON file, so it exists on a fresh `docker compose up`."

## 6. Live failure drill: the ML service goes down (60 s)
*Say:* "Scoring is optional. If the ML service dies, decisions keep flowing with rules only."
1. Terminal: `docker compose --profile app stop ml-service`
2. `cd frontend && npm run seed` (or send a few transactions).
3. Dashboard: new rows show reason code **ML_UNAVAILABLE**. Grafana: **ML calls** show `failure`, then `circuit_open`; **Circuit state** turns red. After about 2 minutes Prometheus shows **MlCircuitOpen** firing at /alerts.
4. `docker compose --profile app start ml-service`. About 30 s later the circuit closes by itself.
"Every other failure was tried too: `docs/observability.md#failure-drills`."

## 7. Admin-only: the Operations tab (60 s)
Sign out, then click **Sign in as Admin** (an analyst never sees this tab; the API answers 403 to them too).
1. Open **Operations**: five status tiles (database, Redis, ML circuit, outbox backlog, stuck PENDING) and the dead-letter viewer.
2. *Live version:* in a terminal `docker compose stop redis`, then send a few transactions (`cd frontend && npm run seed`). About 30 s later the **Redis** tile turns red, the **dead-letter** list fills with `RedisConnectionFailureException`, and **Stuck PENDING** counts up once they are over 2 minutes old.
3. `docker compose start redis`, then click **Re-drive stuck transactions** (set *Older than* to `0` to skip the 2-minute wait). The result reads "Re-queued N, skipped M" and the stuck tile goes back to 0.
4. **Download labelled data** saves the training set as JSON.

## Closing line
"Event-driven pipeline with a transactional outbox, a hybrid rules and ML policy with explanations, a feedback loop with a promotion gate, and the monitoring, drills and tests to back it up, all on synthetic data."

## If something goes wrong
- *Login says too many attempts:* the limit is 5 a minute per IP, wait a minute.
- *Dashboard empty:* run `make seed`.
- *Grafana panels empty:* check http://localhost:9090/targets, both should be UP (`./scripts/setup-local-env.sh` writes the password file Prometheus needs).
- *Recording:* for a 2 to 3 minute screen recording, follow sections 1, 4, 5 and 6 and skip the rest.
