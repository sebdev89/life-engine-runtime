# syntax=docker/dockerfile:1
# life-engine-runtime — multi-stage build (Maven 3.9 + JRE 21 alpine).

# ── Stage 1: compile ─────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src src
RUN mvn package -DskipTests -q

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Build identity for /actuator/info (KAN-195). The build context copies only src/ (no .git), so
# git-commit-id produces no git.properties here — CI passes the real git metadata as build-args and
# BuildIdentityResolver prefers them. service.version + build.timestamp come from build-info.properties
# baked into the jar above. These are plain git facts, never secrets.
ARG GIT_COMMIT=""
ARG GIT_BRANCH=""
ARG GIT_COMMIT_TIME=""
ENV GIT_COMMIT=${GIT_COMMIT} \
    GIT_BRANCH=${GIT_BRANCH} \
    GIT_COMMIT_TIME=${GIT_COMMIT_TIME}

RUN addgroup -S spring && adduser -S spring -G spring
USER spring
WORKDIR /app

COPY --from=build /workspace/target/life-engine-runtime-*.jar app.jar

EXPOSE 8090

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD wget -qO- http://localhost:8090/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
