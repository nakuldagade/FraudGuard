# syntax=docker/dockerfile:1
# ---- build stage: compile and split the jar into layers ----
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
# Dependencies first: this layer is reused until pom.xml changes
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
# Tests need Docker (Testcontainers), so CI runs them separately; the image build only packages
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination /layers

# ---- run stage: JRE only, non-root ----
FROM eclipse-temurin:21-jre
# apt-get upgrade: picks up security fixes for OS packages that are newer than the base image (the image scan flags them)
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home fraudguard
WORKDIR /app
# Layers ordered from least to most frequently changing, so a code change rebuilds only the last one
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER fraudguard
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=docker \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD curl -fs http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
