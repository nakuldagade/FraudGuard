# Backend (Spring Boot 3.5, Java 21)

The API, the Kafka pipeline, the rules and decision policy, security and the admin tools. Overview: [../docs/architecture.md](../docs/architecture.md).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)    # Java 21 is required (newer JDKs break Lombok)
set -a; source ../.env; set +a                      # secrets and keys (see ../.env.example)
mvn spring-boot:run                                 # needs PostgreSQL, Redis and Kafka: docker compose up -d postgres redis kafka
mvn verify                                          # tests (Docker needed for Testcontainers) and the coverage gate
```

API description: http://localhost:8080/swagger-ui/index.html (exported as [../docs/openapi.json](../docs/openapi.json)).
Packages: `controller`, `service` (pipeline, outbox, ML client), `rules`, `decision`, `security`, `entity`, `repository`, `config`.
Migrations: `src/main/resources/db/migration`.
