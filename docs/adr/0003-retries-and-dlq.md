# ADR 0003: Retries with a dead-letter topic

Status: accepted. Date: 2026-11-04.

## Context
A consumer failure is either temporary (Redis or the database is briefly down) or permanent (the message is malformed). Retrying forever
blocks every message behind it; giving up at once loses work that would have succeeded.

## Decision
Spring Kafka's `DefaultErrorHandler`: retry a failed event 3 times after 1 s, 2 s and 4 s, then copy it to `transactions.dlq` and carry on.
`IllegalArgumentException` (malformed or unsupported messages) is treated as permanent and goes to the DLQ without retries. Every dead-lettered
event is counted by cause (`fraudguard_dlq_events_total`). Redis commands time out after 2 s.

## Consequences
- A poison message cannot block the partition (drill 6).
- A dependency outage longer than the retry window dead-letters events and leaves their transactions `PENDING`. That is detected (`fraudguard_transactions_stuck_pending`, the `TransactionsStuckPending` alert) and recoverable with `POST /api/admin/transactions/redrive-pending`, which writes a **new** outbox event per stuck transaction (drill 3).
- **Drill lesson.** With Spring's default 60 s Redis timeout nothing was dead-lettered after 20 s, because each attempt blocked for up to a minute, which would also stall the consumer for minutes. The 2 s timeouts make failures reach the retry and DLQ path in about 25 s.
- The DLQ is not replayed automatically. A person decides, using `GET /api/admin/dlq/summary` to see the causes.

## Alternatives considered
- **Unlimited retries.** Blocks the partition.
- **Separate retry topics with delays.** Better for long outages without blocking, but more topics and more code; the re-drive endpoint covers the demo's needs.
- **Automatic re-drive on a schedule.** Possible later; deliberately manual for now so an operator sees the problem.
