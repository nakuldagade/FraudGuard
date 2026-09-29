# Architecture (Phase 1 draft)

## Transaction lifecycle
`PENDING -> APPROVED | REVIEW | BLOCKED`
Analyst outcomes (Phase 8-9) are stored separately from model decisions:
`CONFIRMED_FRAUD | LEGITIMATE`.

## Sync vs async
| Step | Mode | Why |
|---|---|---|
| Validate + persist transaction, return 202 with ID | Sync | Caller needs fast acknowledgment and the ID |
| Publish `transactions.raw` | Async | Decouples intake from scoring |
| Feature enrichment (Redis), rules, ML score | Async | Can be slower and can fail independently |
| Final decision + status update | Async | Result read via `GET /api/transactions/{id}` |
| Analyst review | Sync (UI) | Human-driven |

## Service responsibilities
- **Backend (Spring Boot):** API, persistence, Kafka producers/consumers, Redis features, rules, decision engine.
- **ML service (FastAPI):** model scoring only, versioned model + feature schema.
- **Frontend (React):** analyst dashboard.
- **PostgreSQL:** system of record. **Redis:** recent-behavior features. **Kafka:** event backbone.

## Event flow
```
Client -> POST /api/transactions -> PostgreSQL (PENDING)
       -> transactions.raw -> [features + rules + ML] -> transactions.scored
       -> [decision engine] -> transactions.decisions -> status update in PostgreSQL
Failures after retries -> transactions.dlq
```

## Kafka topics (local: 3 partitions, replication 1)
`transactions.raw`, `transactions.scored`, `transactions.decisions`, `transactions.dlq`
Partition key: `accountId` (keeps one account's events ordered).

## Local limits
Single broker, no replication: this demonstrates the design, not production durability.
