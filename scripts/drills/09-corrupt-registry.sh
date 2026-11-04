#!/usr/bin/env bash
# Drill 9: models/registry.json is corrupt when the ML service reloads. Expect: HTTP 409, the old model keeps serving,
# ml_model_reloads_total{outcome="failure"} goes up. The file is restored at the end (even on Ctrl+C).
source "$(dirname "$0")/_lib.sh"
say "Drill 9: corrupt model registry"
REG=ml-service/models/registry.json
note "Will back up $REG, write invalid JSON into it, call POST /admin/reload, then restore the file."
pause
cp "$REG" "$REG.drillbak"; trap 'mv -f "$REG.drillbak" "$REG"; echo "   registry restored"' EXIT
note "serving before: $(curl -s $ML/health | python3 -c 'import sys,json; print(json.load(sys.stdin)["model_version"])')"
echo '{ this is not json' > "$REG"
curl -s -w '\n   HTTP %{http_code}\n' -X POST "$ML/admin/reload" -H "X-Admin-Token: $ADMIN_TOKEN"
note "serving after:  $(curl -s $ML/health | python3 -c 'import sys,json; print(json.load(sys.stdin)["model_version"])')  (unchanged)"
curl -s $ML/metrics | grep '^ml_model_reloads_total'
