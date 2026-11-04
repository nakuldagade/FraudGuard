# Everyday commands. `make help` lists them. Everything runs on one machine and uses synthetic demo data.
SHELL := /bin/bash
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null)
export JAVA_HOME
LOAD_ENV = set -a; source .env; set +a

.PHONY: help env up down seed test lint demo openapi

help:           ## show this list
	@grep -E '^[a-z]+:.*##' $(MAKEFILE_LIST) | sed 's/:.*##/ -/' | sort

env:            ## create .env if missing and add any new keys from .env.example
	@./scripts/setup-local-env.sh >/dev/null

up: env         ## build and start everything in Docker (app, Kafka, Postgres, Redis, Prometheus, Grafana)
	docker compose --profile app up -d --build
	docker compose -f infra/observability/docker-compose.yml up -d
	@echo "Dashboard http://localhost:8081   Grafana http://localhost:3000   Prometheus http://localhost:9090"

down:           ## stop everything (data volumes are kept; add -v by hand to delete them)
	docker compose -f infra/observability/docker-compose.yml down
	docker compose --profile app down

seed: env       ## send ~200 demo transactions through the running stack
	@$(LOAD_ENV); cd frontend && npm run seed

test:           ## run all tests (needs Docker for the backend's Testcontainers; Java 21)
	cd backend && mvn -B verify
	cd ml-service && source .venv/bin/activate && python training/train.py >/dev/null && pytest
	cd frontend && npm test

lint:           ## lint the frontend and validate the Docker, Prometheus and Grafana config
	cd frontend && npm run lint && npx tsc --noEmit -p tsconfig.app.json
	docker compose config -q && docker compose --profile app config -q
	docker run --rm -v "$$PWD/infra/observability:/w" -w /w --entrypoint promtool prom/prometheus:v2.55.1 check rules alerts.yml
	jq empty infra/observability/grafana/dashboards/*.json

demo: up        ## start, seed labelled data, retrain, promote the new model and load it (about 3 minutes)
	@echo "Waiting for the backend ..."; until curl -sf http://localhost:8080/actuator/health/readiness >/dev/null; do sleep 3; done
	@$(LOAD_ENV); cd frontend && npm run seed -- --label
	@$(LOAD_ENV); docker compose --profile app exec -T -e FRAUDGUARD_ADMIN_USERNAME -e FRAUDGUARD_ADMIN_PASSWORD ml-service \
	    python training/retrain.py --api http://backend:8080 --split stratified --promote
	@$(LOAD_ENV); curl -s -X POST http://127.0.0.1:8000/admin/reload -H "X-Admin-Token: $$ADMIN_TOKEN"; echo
	@echo "Done. Open the dashboard and look at the Model feedback card."

openapi:        ## re-export docs/openapi.json from the running backend
	curl -s http://localhost:8080/v3/api-docs | python3 -c "import sys,json; json.dump(json.load(sys.stdin), open('docs/openapi.json','w'), indent=2, sort_keys=True)"
