# ADR 0002: Transactional outbox

Status: accepted. Date: 2026-11-04.

## Context
Saving a transaction and publishing its event are two different systems. Doing them one after the other can lose the event (saved, then the
publish fails) or publish an event for something that was never saved (published, then the save fails).

## Decision
The transaction row and an `outbox_events` row are written in **one database transaction**. A scheduled relay reads unsent outbox rows
oldest first, publishes them to Kafka and sets `published_at`. The event id is the outbox row id.

## Consequences
- No lost events: if Kafka is down the API still answers 202, the backlog (`fraudguard_outbox_pending`) grows, and it drains in order when Kafka returns (drill 4).
- Delivery is at least once. The consumer stores the id of the last event it applied and skips a repeat (drill 7).
- **Throughput lesson (Phase 11).** The first relay sent 50 events per round, one at a time, then slept a second. At 50 req/s the backlog reached 645 events and the end-to-end p95 was 12.8 s. It now sends a batch of 500 together, confirms them afterwards and polls every 200 ms: p95 0.23 s. The trade-off: if one send fails mid-batch, later events may already be in Kafka and are sent again next round.
- Publishing is delayed by up to the polling interval. A failure after the Kafka send but before `published_at` is saved also causes a repeat.

## Alternatives considered
- **Publish inside the API request, then save.** Loses events or publishes phantom ones.
- **Kafka transactions across the database and Kafka.** Not possible: they are separate transaction managers.
- **Change data capture (Debezium) on the outbox table.** Lower latency and no polling, but another component to run; polling is enough here.
