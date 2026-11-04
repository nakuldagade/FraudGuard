#!/usr/bin/env bash
# Drill 5: PostgreSQL is down. Expect: API errors (5xx), readiness 503, Hikari connections pending; automatic
# recovery when the database returns.
source "$(dirname "$0")/_lib.sh"
say "Drill 5: PostgreSQL down"
note "Will STOP Postgres, call the API, then START it again."
pause
TOKEN="$(admin_token)"
docker compose stop postgres >/dev/null; note "postgres stopped"; sleep 5
note "list call HTTP code: $(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$API/api/transactions")"
note "ingest HTTP code:    $(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/transactions" -H 'Content-Type: application/json' -H "X-API-Key: $INGEST_API_KEY" -H "Idempotency-Key: drill-$RUN" -d '{"accountId":"drill-pg","transactionType":"PAYMENT","amount":1,"currency":"INR"}')"
note "readiness HTTP code: $(readiness)  (expect 503)"
metric 'hikaricp_connections_(active|pending)'
docker compose start postgres >/dev/null; note "postgres starting"; sleep 20
note "readiness after restart: $(readiness)  (expect 200)"
note "list call HTTP code: $(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(admin_token)" "$API/api/transactions")"
