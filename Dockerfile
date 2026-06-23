# syntax=docker/dockerfile:1
# life-engine-runtime — multi-stage build (Maven 3.9 + JRE 21 alpine).
#
# Build:  docker build -t life-engine/runtime:local .
# Run:    docker compose -f infra/docker-compose.platform.yml up -d --build

# ── Stage 1: compile ────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

# Resolve deps before copying src so this layer is cached on pom-only changes.
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src src
RUN mvn package -DskipTests -q

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S spring && adduser -S spring -G spring
USER spring
WORKDIR /app

COPY --from=build /workspace/target/life-engine-runtime-*.jar app.jar

EXPOSE 8090

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD wget -qO- http://localhost:8090/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
