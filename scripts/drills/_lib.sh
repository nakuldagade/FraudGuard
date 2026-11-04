#!/usr/bin/env bash
# Shared helpers for the failure drills. Each drill prints what it will do and waits for Enter before it
# changes anything. Set DRILL_YES=1 to skip the waiting (used when recording results).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
set -a; source .env; set +a
API="${API_URL:-http://localhost:8080}"
ML="http://127.0.0.1:8000"
RUN="$(date +%s)"

say()   { printf '\n\033[1m== %s\033[0m\n' "$*"; }
note()  { printf '   %s\n' "$*"; }
pause() {
  if [ "${DRILL_YES:-}" = 1 ]; then echo "   (DRILL_YES=1: continuing)"; else read -r -p "   Press Enter to continue, Ctrl+C to abort... "; fi
}

# Prometheus text from the backend (needs the METRICS login)
metrics()  { curl -s -u "$FRAUDGUARD_METRICS_USERNAME:$FRAUDGUARD_METRICS_PASSWORD" "$API/actuator/prometheus"; }
metric()   { metrics | grep -E "$1" | grep -v '^#' || true; }
readiness(){ curl -s -o /dev/null -w '%{http_code}' "$API/actuator/health/readiness"; }

# Log in once per script: the login endpoint allows only 5 attempts a minute per IP
ADMIN_TOKEN_CACHE=""
admin_token() {
  [ -n "$ADMIN_TOKEN_CACHE" ] && { printf '%s' "$ADMIN_TOKEN_CACHE"; return; }
  ADMIN_TOKEN_CACHE="$(login_admin)"; printf '%s' "$ADMIN_TOKEN_CACHE"
}
login_admin() {
  curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$FRAUDGUARD_ADMIN_USERNAME\",\"password\":\"$FRAUDGUARD_ADMIN_PASSWORD\"}" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])'
}
api() { curl -s -H "Authorization: Bearer $TOKEN" "$@"; }
TOKEN="$(admin_token)"   # api <curl args>
pending_count() { api "$API/api/transactions/summary" | python3 -c 'import sys,json; print(json.load(sys.stdin)["byStatus"].get("PENDING",0))'; }

# send_tx <count>: create <count> transactions (prints the HTTP codes and remembers the ids in LAST_IDS)
LAST_IDS=()
send_tx() {
  LAST_IDS=(); local codes=""
  for i in $(seq 1 "$1"); do
    out=$(curl -s -w '\n%{http_code}' -X POST "$API/api/transactions" -H 'Content-Type: application/json' \
      -H "X-API-Key: $INGEST_API_KEY" -H "Idempotency-Key: drill-$RUN-$RANDOM-$i" \
      -d "{\"accountId\":\"drill-$RUN-$i\",\"transactionType\":\"PAYMENT\",\"amount\":25.50,\"currency\":\"INR\",\"country\":\"IN\",\"merchantId\":\"m-1\"}")
    codes="$codes $(echo "$out" | tail -1)"
    LAST_IDS+=("$(echo "$out" | head -1 | python3 -c 'import sys,json; print(json.load(sys.stdin).get("id",""))' 2>/dev/null)")
  done
  note "HTTP codes:$codes"
}
reason_of() { api "$API/api/transactions/$1" | python3 -c 'import sys,json; t=json.load(sys.stdin); print(t["status"], t.get("reasonCodes"))'; }

# Target the uvicorn process itself (not whatever else, such as Docker's port proxy, listens on :8000)
ml_pid() { pgrep -f 'uvicorn app.main:app' | head -1; }
stop_ml() { local p; p="$(ml_pid)"; [ -n "$p" ] && kill "$p" && sleep 2; }
start_ml() {
  ( cd ml-service && source .venv/bin/activate && nohup python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 \
      > "${TMPDIR:-/tmp}/fraudguard-ml.log" 2>&1 & )
  for _ in $(seq 1 20); do curl -sf "$ML/health" >/dev/null && return 0; sleep 1; done
  echo "   ML service did not start"; return 1
}
