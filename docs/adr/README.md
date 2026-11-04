# Architecture decision records

Short notes on why the system is built the way it is: context, the decision, what it costs, and what else was considered. They describe a demo on synthetic data.

| ADR | Decision |
|---|---|
| [0001](0001-event-driven-pipeline-with-kafka.md) | Event-driven pipeline with Kafka |
| [0002](0002-transactional-outbox.md) | Transactional outbox |
| [0003](0003-retries-and-dlq.md) | Retries with a dead-letter topic |
| [0004](0004-redis-velocity-features.md) | Redis for velocity features |
| [0005](0005-hybrid-rules-and-ml-policy.md) | Hybrid rules and ML decision policy |
| [0006](0006-optional-ml-with-circuit-breaker.md) | Optional ML with a circuit breaker |
| [0007](0007-feedback-loop-promotion-gate-calibration.md) | Feedback loop with a promotion gate and score calibration |
| [0008](0008-stateless-jwt.md) | Stateless JWT for the analyst API |
| [0009](0009-prometheus-and-grafana.md) | Prometheus and Grafana for observability |
