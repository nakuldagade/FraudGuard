# Real-Time Fraud Detection Platform

Event-driven fintech system that ingests transactions, scores risk, explains decisions,
and supports analyst review. Stack: Java 21, Spring Boot, Kafka, PostgreSQL, Redis,
Python/FastAPI, scikit-learn, React, Docker, Prometheus/Grafana.

> Scope note: this is a local demo built on synthetic data. It demonstrates architecture
> and engineering decisions, not real-world fraud detection performance.

## Status
Phase 1 (skeleton) done. See [docs/architecture.md](docs/architecture.md).

## Quick start
```bash
cp .env.example .env        # then edit the password
docker compose up -d
docker compose ps           # postgres, redis, kafka, ml-service healthy; kafka-init exited 0
curl localhost:8000/health  # {"status":"ok","model_version":null}
```

Verify topics:
```bash
docker compose logs kafka-init
```

Stop: `docker compose down` (add `-v` to wipe data).

## Layout
```
backend/      Spring Boot service (Phase 2+)
ml-service/   FastAPI scoring service
frontend/     React dashboard (Phase 8)
docs/         Architecture and design notes
```
