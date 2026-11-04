#!/usr/bin/env bash
# Drill 2: the ML service hangs (alive but not answering). Expect: 1.5 s read timeouts count as failures and the
# circuit opens; pipeline latency rises a little while it does.
source "$(dirname "$0")/_lib.sh"
say "Drill 2: ML service hangs"
note "Will FREEZE the ML process (kill -STOP), send transactions, then UNFREEZE it (kill -CONT)."
pause
PID="$(ml_pid)"; [ -z "$PID" ] && { echo "ML service is not running"; exit 1; }
kill -STOP "$PID"; note "frozen pid $PID"
trap 'kill -CONT "$PID" 2>/dev/null' EXIT      # never leave the process frozen, even on Ctrl+C
send_tx 6; sleep 14
say "After the hang"; metric 'fraudguard_ml_circuit_open|fraudguard_ml_calls_total|fraudguard_ml_call_seconds_max'
kill -CONT "$PID"; note "unfrozen"; sleep 32
send_tx 2; sleep 3; metric 'fraudguard_ml_circuit_open|fraudguard_ml_calls_total'
