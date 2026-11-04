# ADR 0009: Prometheus and Grafana for observability

Status: accepted. Date: 2026-11-04.

## Context
Failures here are quiet: an ML outage still produces decisions, and a Redis outage leaves transactions waiting. Without metrics nobody would know.

## Decision
Micrometer metrics from the backend and `prometheus-client` metrics from the ML service, scraped by Prometheus, shown on a Grafana dashboard that is provisioned from a JSON file,
with 12 alert rules in a file that is unit-tested with `promtool`. Names are `fraudguard.*` in code and `fraudguard_*` in Prometheus. There is no Alertmanager.

## Consequences
- The failure modes of the pipeline each have a metric and, where it matters, an alert and a runbook ([observability.md](../observability.md)).
- Dashboard and alerts are code: reviewable, reproducible, validated in CI.
- Alerts are only visible in the Prometheus UI; nothing is sent to anyone. Thresholds are demo-sized and untuned.
- Metrics are pulled from `/actuator/prometheus`, which needs the METRICS login, so Prometheus carries a password file.
- Not covered: tracing, logs aggregation.

## Alternatives considered
- **A hosted APM.** Less to run, but not self-contained.
- **OpenTelemetry end to end.** Broader, and a lot more to learn for the same demo.
- **Alertmanager with Slack or email.** The obvious next step; it needs an account and secrets, so it is left out.
