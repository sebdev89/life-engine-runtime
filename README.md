# life-engine-runtime

Generic agent runtime (Java 21, Spring Boot 3.4, WebFlux). In-memory runs and SSE events; optional vLLM agent workflow. No auth, DB, or vertical modules.

## Run locally

```bash
mvn spring-boot:run
```

Default port: **8090**.

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

LLM tests use MockWebServer; no live vLLM required in CI.
