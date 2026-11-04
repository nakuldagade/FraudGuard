#!/usr/bin/env bash
# Drill 3: Redis is down longer than the Kafka retry window. Expect: the API still accepts (202) because ingestion
# does not use Redis; the consumer retries (1/2/4 s) then dead-letters; transactions stay PENDING; readiness is 503.
# Recovery: start Redis, call the re-drive endpoint, the transactions get decided.
source "$(dirname "$0")/_lib.sh"
say "Drill 3: Redis down"
note "Will STOP Redis (docker compose), send transactions, restart Redis, then re-drive stuck transactions."
pause
docker compose stop redis >/dev/null; note "redis stopped"
send_tx 5; sleep 40     # retries (1, 2, 4 s) plus 2 s Redis timeouts per attempt, so about 25 s until the DLQ
say "While Redis is down"
note "readiness HTTP code: $(readiness)  (expect 503)"
note "PENDING now: $(pending_count)"
metric 'fraudguard_dlq_events_total|fraudguard_event_processing_seconds_count\{.*error|fraudguard_transactions_stuck_pending'
note "Note: with the default stuck-after of 120 s the TransactionsStuckPending alert needs another ~2 min + 3 min."
docker compose start redis >/dev/null; sleep 8
note "redis started; readiness: $(readiness)"
say "Recovery: re-drive (olderThanSeconds=0 only to keep the drill short; in real use keep the default 120)"
api -X POST "$API/api/admin/transactions/redrive-pending?olderThanSeconds=0&limit=500"; echo
sleep 12
note "PENDING after re-drive: $(pending_count)  (expect 0)"
