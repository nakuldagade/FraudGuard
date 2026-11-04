#!/usr/bin/env bash
# Drill 7: the same event is delivered twice. Expect: outcome="duplicate" is counted and the status is unchanged.
source "$(dirname "$0")/_lib.sh"
say "Drill 7: duplicate event"
note "Will create one transaction, wait for its decision, then republish its exact outbox event."
pause
send_tx 1; ID="${LAST_IDS[0]}"; sleep 5; note "decided: $(reason_of "$ID")"
row="$(docker compose exec -T postgres psql -U "${POSTGRES_USER:-fraud_user}" -d "${POSTGRES_DB:-fraud}" -tA \
  -c "select message_key || '|' || payload from outbox_events where aggregate_id='$ID'")"
key="${row%%|*}"; payload="${row#*|}"
before="$(metric 'fraudguard_event_processing_seconds_count\{.*duplicate' )"
printf '%s:%s\n' "$key" "$payload" | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9092 --topic transactions.raw --property parse.key=true --property key.separator=: >/dev/null 2>&1
sleep 5
note "duplicate counter before: ${before:-none}"
note "duplicate counter after:  $(metric 'fraudguard_event_processing_seconds_count\{.*duplicate')"
note "status after: $(reason_of "$ID")  (unchanged)"
