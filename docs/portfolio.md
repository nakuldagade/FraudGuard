# Portfolio material

Everything here is based on what was built and measured. **Do not add numbers that are not in [performance.md](performance.md),
[testing.md](testing.md) or [observability.md](observability.md), and always say the data is synthetic.** The project is a
learning and portfolio project, not a production system.

## Short project summary

FraudGuard is a real-time fraud detection platform I built end to end: a Spring Boot backend that accepts payments and decides on them
asynchronously through Kafka (rules plus an ML anomaly score, combined by a versioned, explainable policy), a FastAPI model service,
a React analyst dashboard, and a feedback loop where analyst verdicts retrain the model behind a promotion gate. It includes monitoring
(Prometheus, Grafana, alert rules), nine failure drills I ran against the real stack, a test suite with coverage gates, load tests,
and a Docker and CI setup. It runs on synthetic data and is deliberately honest about what that means.

## Résumé bullets

Pick three or four. Each number is from a measurement documented in the repository (one laptop, synthetic traffic).

- Built an event-driven fraud decision pipeline (Spring Boot, Kafka, Redis, PostgreSQL, FastAPI) with a transactional outbox, retries and a dead-letter topic; ingestion stayed at p95 12 ms with 0% errors at 50 requests/second.
- Load-tested the pipeline, found the outbox relay capped throughput near 50 events/second, and batched it: time from "accepted" to "decided" at 50 requests/second dropped from a p95 of 12.8 s to 0.23 s.
- Designed a hybrid rules and ML decision policy where ML alone never blocks, with a circuit breaker so scoring is optional; every decision stores reason codes, a plain-English explanation and an inputs snapshot.
- Added a feedback loop (analyst labels, retraining, a promotion gate, hot model reload, score calibration) and measured it end to end on synthetic labels.
- Ran nine failure drills (ML, Redis, Kafka and PostgreSQL outages, poison and duplicate messages, restart under load, corrupt model registry), which exposed a 60 s Redis timeout that stalled recovery, fixed with a 2 s timeout; built a re-drive endpoint and alerts for stuck transactions.
- Secured the system with role-based JWT login, an API key for ingestion, token-protected ML routes and protected metrics; found and fixed real issues along the way (Starlette advisories flagged by `pip-audit`, an nginx `Host` header bug that broke browser logins).
- Shipped 142 backend tests (94.6% line coverage, enforced in the build), 45 ML tests, 35 frontend tests and an end-to-end browser test, plus Java/Python contract tests against a shared JSON Schema.
- Packaged everything with multi-stage non-root Docker images, one-command Compose startup, GitHub Actions CI, CodeQL, Trivy and Dependabot configuration.

## LinkedIn post (draft)

> I just finished a project I've been building to learn how fraud detection systems fit together: FraudGuard.
>
> A payment comes in, gets acknowledged immediately, and is decided in the background by rules plus an ML anomaly score. Every decision
> comes with reasons in plain English. Analysts review the risky ones, and their verdicts retrain the model, but a new model only goes live
> if it beats the old one on held-out data.
>
> The most useful part was breaking it on purpose. I stopped Redis, Kafka and PostgreSQL, froze the ML service, sent garbage messages and
> restarted the backend under load. That found a real problem (a 60 second default timeout that stalled recovery), and a load test found
> another (my outbox relay limited throughput; fixing it took the p95 decision delay from 12.8 s to 0.23 s on my laptop).
>
> Stack: Java 21 and Spring Boot, Kafka, Redis, PostgreSQL, FastAPI and scikit-learn, React, Prometheus and Grafana, Docker, GitHub Actions.
>
> One honest note: it runs entirely on synthetic data, so none of the numbers say anything about real fraud. It's a project about
> engineering, not a claim about accuracy.
>
> Repo and write-up: <link>

## Interview Q&A

**Why Kafka and an asynchronous design instead of scoring inside the request?**
The caller needs a fast acknowledgement, while the decision depends on Redis, an ML call and a database write that can each fail or slow down. Async keeps intake independent of them (ingestion p95 12 ms) and lets failures retry without failing the payment. The cost is eventual consistency: the status is `PENDING` for a short moment ([ADR 0001](adr/0001-event-driven-pipeline-with-kafka.md)).

**What is the transactional outbox and why use it?**
Saving a transaction and publishing to Kafka are two systems, so doing both naively can lose an event or publish a phantom one. I save the transaction and an outbox row in one database transaction and have a relay publish the rows. Delivery is at least once, so the consumer ignores an event id it already applied ([ADR 0002](adr/0002-transactional-outbox.md)).

**What went wrong in performance testing?**
At 50 req/s the API was fast but decisions took 12.8 s at p95. Kafka lag was zero and the outbox backlog reached 645, which pointed at the relay: it sent 50 events per round, one at a time, then slept a second. Batching it (500 per round, confirmations collected afterwards, 200 ms polling) brought p95 to 0.23 s. The trade-off is that after a mid-batch failure some events can be delivered twice, which the consumer tolerates.

**How do you handle a duplicate or poison message?**
A repeated event id is recognised and skipped (counted as `duplicate`). A malformed message is treated as permanent and goes straight to the dead-letter topic without retries, so it cannot block the partition. Other failures retry after 1, 2 and 4 seconds first.

**What happens when the ML service is down?**
Scoring is optional: a 1.5 s timeout, and after 3 failures a circuit breaker skips calls for 30 s. Decisions continue with rules only and carry `ML_UNAVAILABLE`. The cost is that fewer risky transactions are caught, because ML is what sends clean-looking ones to review. This is visible on the dashboard and alerted.

**Why must ML never block on its own?**
The score is a ranking from an anomaly model, not a probability of fraud, and it was never validated on real data. A block needs a second signal, so the policy only blocks when a rule already asked for review and the ML score is very high.

**How does the feedback loop avoid making the model worse?**
A challenger is promoted only if there are enough labels (60, at least 15 per class) and it improves PR-AUC by at least 0.02 on held-out data. A promoted model's scores are calibrated onto the policy's scale, a reload that fails keeps the old model, and rollback is a one-line registry edit.

**Can you trust the model's improvement numbers?**
Not as real-world accuracy. The labels in the demo are synthetic: they come from the scenario that generated each row. The improvement shows the loop works and that the model finds the generator's patterns. Real labels would also be biased, since analysts mostly see transactions the system already flagged.

**What are the security weaknesses?**
JWTs cannot be revoked before they expire (60 minutes), there are no refresh tokens, the dashboard stores the token in `sessionStorage` (XSS would expose it), the login limit is in memory and per IP (behind a proxy everyone shares an address), and ML and Prometheus use shared secrets. Anything not explicitly listed in the route rules defaults to admin-only.

**What did the failure drills teach you?**
The Redis one: with the default 60 s timeout a Redis outage barely dead-lettered anything after 20 s, because each retry blocked for up to a minute. It would also have stalled the consumer for minutes. A 2 s timeout made the retry and dead-letter path actually work, and the re-drive endpoint then recovered the stuck transactions.

**What would you do next?**
Alertmanager notifications, scheduled retraining with drift monitoring, refresh tokens, keyset paging instead of a `count(*)` per page, and an offline benchmark of the model on a public dataset clearly labelled as such.

**What is not production-ready?**
One Kafka broker without replication, one database, secrets in an env file, in-memory rate limiting and circuit state, manual retraining and re-drive, no notification of alerts, and performance numbers from a single laptop run per test.
