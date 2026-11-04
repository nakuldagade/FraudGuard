#!/usr/bin/env bash
# Drill 6: a non-JSON message on transactions.raw. Expect: it goes straight to transactions.dlq (no retries) with
# cause IllegalArgumentException, and the consumer carries on.
source "$(dirname "$0")/_lib.sh"
say "Drill 6: poison message"
note "Will publish the text 'this-is-not-json-$RUN' to transactions.raw."
pause
before="$(api "$API/api/admin/dlq/summary" | python3 -c 'import sys,json; print(json.load(sys.stdin)["totalMessages"])')"
echo "this-is-not-json-$RUN" | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9092 --topic transactions.raw >/dev/null 2>&1
sleep 5
api "$API/api/admin/dlq/summary" | python3 -c "
import sys,json; d=json.load(sys.stdin)
print('   DLQ messages: before=$before after=%d' % d['totalMessages']); print('   causes (recent):', d['recentByException'])"
metric 'fraudguard_dlq_events_total'
