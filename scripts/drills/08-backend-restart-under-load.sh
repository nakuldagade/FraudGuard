#!/usr/bin/env bash
# Drill 8: restart the backend while traffic is flowing. Expect: nothing lost. Transactions accepted before the
# restart are decided after it (Kafka offsets and the outbox are durable); PENDING returns to 0, with re-drive if needed.
# BACKEND_START_CMD can override how the backend is started.
source "$(dirname "$0")/_lib.sh"
say "Drill 8: backend restart under load"
note "Will run the seed script, stop the backend (SIGTERM = graceful) mid-run, start it again, and count PENDING."
pause
start_backend() {
  if [ -n "${BACKEND_START_CMD:-}" ]; then eval "$BACKEND_START_CMD"; return; fi
  ( cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" nohup mvn -q spring-boot:run \
      > "${TMPDIR:-/tmp}/fraudguard-backend.log" 2>&1 & )
}
( cd frontend && npm run seed >/tmp/fraudguard-seed.log 2>&1 ) &
SEED=$!
sleep 25
PID="$(lsof -nP -iTCP:8080 -sTCP:LISTEN -t | head -1)"; note "stopping backend pid $PID"; kill "$PID"
for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
note "backend stopped; the seed script keeps getting connection errors meanwhile"
start_backend; for _ in $(seq 1 60); do curl -sf "$API/actuator/health/liveness" >/dev/null && break; sleep 3; done
wait "$SEED" 2>/dev/null; note "seed script exit; tail of its log:"; tail -4 /tmp/fraudguard-seed.log | sed 's/^/     /'
sleep 20
note "PENDING after restart: $(pending_count)"
if [ "$(pending_count)" != 0 ]; then
  note "re-driving the stuck ones"; api -X POST "$API/api/admin/transactions/redrive-pending?olderThanSeconds=0&limit=500"; echo; sleep 15
  note "PENDING after re-drive: $(pending_count)"
fi
note "Outbox backlog: $(metric 'fraudguard_outbox_pending')"
