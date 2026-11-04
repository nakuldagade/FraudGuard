#!/usr/bin/env bash
# One-time local setup: makes sure .env has every key from .env.example (it never overwrites a value you
# already set) and writes the Prometheus password file from FRAUDGUARD_METRICS_PASSWORD.
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env ] || { cp .env.example .env; echo "created .env from .env.example"; }

added=0
while IFS= read -r line; do
  key="${line%%=*}"
  case "$line" in ''|\#*|' '*) continue ;; esac
  [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
  if ! grep -qE "^${key}=" .env; then
    echo "$line" >> .env
    echo "added $key to .env"
    added=$((added + 1))
  fi
done < .env.example

password="$(grep -E '^FRAUDGUARD_METRICS_PASSWORD=' .env | head -1 | cut -d= -f2- | sed 's/[[:space:]]*#.*$//')"
mkdir -p infra/observability/secrets
printf '%s' "$password" > infra/observability/secrets/metrics_password
chmod 600 infra/observability/secrets/metrics_password
echo "wrote infra/observability/secrets/metrics_password"
echo "done ($added new key(s)). Load it with:  set -a; source .env; set +a"
