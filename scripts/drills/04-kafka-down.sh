#!/usr/bin/env bash
# Drill 4: Kafka is down. Expect: the API still returns 202; fraudguard_outbox_pending grows; after Kafka returns the
# backlog drains in order with no loss.
source "$(dirname "$0")/_lib.sh"
say "Drill 4: Kafka down"
note "Will STOP Kafka, send transactions, then START Kafka and watch the outbox drain."
pause
docker compose stop kafka >/dev/null; note "kafka stopped"
send_tx 8; sleep 15
metric 'fraudguard_outbox_pending'
note "PENDING now: $(pending_count)"
docker compose start kafka >/dev/null; note "kafka starting (about 20 s)"
for i in $(seq 1 12); do sleep 5; echo "   outbox: $(metric 'fraudguard_outbox_pending')  pending tx: $(pending_count)"; done
