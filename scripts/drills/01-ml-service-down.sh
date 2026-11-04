#!/usr/bin/env bash
# Drill 1: the ML service is down. Expect: decisions continue as rules-only (ML_UNAVAILABLE), the circuit opens
# after 3 failures for 30 s, and after a restart one trial call closes it again.
source "$(dirname "$0")/_lib.sh"
say "Drill 1: ML service down"
note "Will STOP the ML service (uvicorn on :8000), send transactions, then START it again."
pause
stop_ml; note "ML listener now: '$(ml_pid)' (empty = stopped)"
send_tx 6; sleep 4
for id in "${LAST_IDS[@]:0:3}"; do note "decision: $(reason_of "$id")"; done
say "Circuit and ML call counters"; metric 'fraudguard_ml_circuit_open|fraudguard_ml_calls_total'
say "Restarting the ML service; waiting out the 30 s open window"; start_ml; sleep 32
send_tx 2; sleep 3
metric 'fraudguard_ml_circuit_open|fraudguard_ml_calls_total'
note "Expected: circuit_open 0 again, a new 'success' call counted."
