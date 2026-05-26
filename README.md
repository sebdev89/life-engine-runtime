# life-engine-runtime

Generic agent runtime (Java 21, Spring Boot 3.4, WebFlux). Persists runs, events, agent stages, and LLM call records to PostgreSQL via R2DBC; SSE event stream; optional vLLM agent workflow. No auth or vertical modules.

## Run locally

```bash
mvn spring-boot:run
```

Default port: **8090**.

### Database (PostgreSQL via R2DBC + Flyway)

The runtime persists execution history into the `life_engine_runtime` database. Flyway runs on startup over JDBC and creates four tables (`runtime_run`, `runtime_event`, `runtime_agent_stage_record`, `runtime_llm_call_record`); reads/writes at runtime go through R2DBC so the WebFlux event loop is never blocked. No CryptoBot- or vertical-specific tables live in this DB.

**One-time setup** (matches the defaults in `application.yml`):

```bash
sudo -u postgres psql -c "CREATE ROLE life WITH LOGIN PASSWORD 'life';"
sudo -u postgres psql -c "CREATE DATABASE life_engine_runtime OWNER life;"
```

Or via Docker:

```bash
docker run -d --name life-engine-pg \
  -e POSTGRES_USER=life -e POSTGRES_PASSWORD=life -e POSTGRES_DB=life_engine_runtime \
  -p 5432:5432 postgres:16-alpine
```

**Configuration** (`src/main/resources/application.yml`, override via env):

| Property | Default | Override env |
|----------|---------|--------------|
| `spring.r2dbc.url` | `r2dbc:postgresql://localhost:5432/life_engine_runtime` | `SPRING_R2DBC_URL` |
| `spring.r2dbc.username` | `life` | `SPRING_R2DBC_USERNAME` |
| `spring.r2dbc.password` | `life` | `SPRING_R2DBC_PASSWORD` |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/life_engine_runtime` | `SPRING_DATASOURCE_URL` |
| `spring.flyway.locations` | `classpath:db/migration` | — |
| `lifeengine.runtime.persistence.type` | `r2dbc` | `LIFEENGINE_RUNTIME_PERSISTENCE_TYPE` |

**In-memory fallback** (no Postgres needed — runs are lost on restart):

```bash
LIFEENGINE_RUNTIME_PERSISTENCE_TYPE=inmem \
  SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration \
  mvn spring-boot:run
```

The test profile (`src/test/resources/application-test.yml`) already disables R2DBC/Flyway/JDBC autoconfig and pins `persistence.type=inmem`, so existing unit tests stay hermetic.

### vLLM (for `demo.llm.workflow`)

Point at an OpenAI-compatible server (default `http://localhost:8000`):

```yaml
runtime:
  llm:
    base-url: http://localhost:8000
    model: Qwen/Qwen2.5-Coder-7B-Instruct
    api-key: local-dev
    timeout: 30s
```

### Angular cockpit UI

See [life-engine-runtime-ui](../life-engine-runtime-ui/README.md) — `npm start` on port **4200**.

Static fallback: **http://localhost:8090/**

## API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/actuator/health` | Actuator health |
| POST | `/api/runtime/runs` | Start a workflow run |
| GET | `/api/runtime/runs/{runId}` | Get run status |
| GET | `/api/runtime/runs/{runId}/events` | SSE event stream (replay + live) |
| POST | `/api/runtime/runs/{runId}/cancel` | Cancel a non-terminal run |

### Start run body

```json
{
  "workflowId": "demo.llm.workflow",
  "input": "text to summarize and classify",
  "correlationId": "optional"
}
```

- **`demo.llm.workflow`** (default): `SummarizerAgent` → vLLM → `ClassifierAgent` → vLLM
- **`demo.no-llm.workflow`**: legacy fake pipeline (no LLM)

## LLM workflow events

`WORKFLOW_STARTED` → `AGENT_STARTED` → `LLM_REQUESTED` → `LLM_COMPLETED` → `AGENT_COMPLETED` (×2 agents) → `WORKFLOW_COMPLETED` → `RUN_COMPLETED`

On failure: `LLM_FAILED`, `AGENT_FAILED`, `RUN_FAILED` (no silent fallback).

## Tests

```bash
mvn test
```

LLM tests use MockWebServer; no live vLLM required in CI. The R2DBC integration test (`R2dbcRunStoreTest`) starts an ephemeral `postgres:16-alpine` via Testcontainers when Docker is available, and is **automatically skipped** when it is not — so the suite stays green on machines without a Docker daemon.
