#!/usr/bin/env bash
# Runs one k6 test from its Docker image and saves the summary to perf/results/.
#   ./perf/run.sh ingest    ramp to RATE (default 50/s) for 5 min; thresholds: p95 < 300 ms, errors < 1%
#   ./perf/run.sh e2e       same load, then reads p95 pipeline latency from Prometheus (target: under 2 s at 50/s)
#   ./perf/run.sh read      list/filter queries on the large table (seed first); p95 < 200 ms
#   ./perf/run.sh soak      10 req/s for DURATION (default 30m) while recording heap
# Needs: the whole stack running, .env present, Docker. Override with RATE=, RAMP=, HOLD=, DURATION=.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
TEST="${1:?usage: run.sh ingest|e2e|read|soak}"
RUN_ID="$(date +%s)"
OUT="perf/results/${TEST}-${RUN_ID}"
PROM="http://localhost:9090"
mkdir -p perf/results

prom() { curl -s -G "$PROM/api/v1/query" --data-urlencode "query=$1" | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(r[0]["value"][1] if r else "n/a")'; }

START=$(date +%s)
case "$TEST" in e2e) SCRIPT=ingest.js ;; *) SCRIPT="$TEST.js" ;; esac
docker run --rm -i --add-host host.docker.internal:host-gateway \
  -e RATE -e RAMP -e HOLD -e DURATION -e RUN_ID="$RUN_ID" -e INGEST_API_KEY \
  -e FRAUDGUARD_ADMIN_USERNAME -e FRAUDGUARD_ADMIN_PASSWORD \
  -v "$PWD/perf:/perf" grafana/k6:latest run --quiet --summary-export="/perf/results/$(basename "$OUT").json" "/perf/$SCRIPT"
K6_EXIT=$?
END=$(date +%s); WINDOW=$((END - START + 30))

if [ "$TEST" = e2e ] || [ "$TEST" = ingest ] || [ "$TEST" = soak ]; then
  echo; echo "Waiting 60 s for the pipeline to drain, then reading Prometheus ..."; sleep 60
  WINDOW=$((WINDOW + 60))
  echo "pipeline latency p50 over the run: $(prom "histogram_quantile(0.50, sum by (le) (increase(fraudguard_pipeline_latency_seconds_bucket[${WINDOW}s])))") s"
  echo "pipeline latency p95 over the run: $(prom "histogram_quantile(0.95, sum by (le) (increase(fraudguard_pipeline_latency_seconds_bucket[${WINDOW}s])))") s"
  echo "pipeline latency p99 over the run: $(prom "histogram_quantile(0.99, sum by (le) (increase(fraudguard_pipeline_latency_seconds_bucket[${WINDOW}s])))") s"
  echo "decisions made during the run:     $(prom "sum(increase(fraudguard_decisions_total[${WINDOW}s]))")"
  echo "max Kafka consumer lag:            $(prom "max_over_time(max(kafka_consumer_fetch_manager_records_lag_max)[${WINDOW}s:15s])")"
  echo "max outbox backlog:                $(prom "max_over_time(fraudguard_outbox_pending[${WINDOW}s])")"
  echo "stuck PENDING now:                 $(prom "fraudguard_transactions_stuck_pending")"
  echo "DLQ events during the run:         $(prom "sum(increase(fraudguard_dlq_events_total[${WINDOW}s])) or vector(0)")"
fi
if [ "$TEST" = soak ]; then
  echo; echo "Heap (MB), lowest value in each 5-minute slice (the low points follow garbage collections):"
  slices=$(( (END - START) / 300 ))
  for i in $(seq 0 $((slices - 1))); do
    off=$(( END - START - (i + 1) * 300 )); [ "$off" -lt 0 ] && off=0
    t=$(( START + (i + 1) * 300 ))
    v=$(curl -s -G "$PROM/api/v1/query" --data-urlencode "query=min_over_time(sum(jvm_memory_used_bytes{area=\"heap\"})[300s:15s])/1048576" --data-urlencode "time=$t" | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(round(float(r[0]["value"][1]),1) if r else "n/a")')
    echo "  minute $(( (i + 1) * 5 )): $v"
  done
fi
echo; echo "k6 summary saved to $OUT.json   (k6 exit code $K6_EXIT)"
exit $K6_EXIT
