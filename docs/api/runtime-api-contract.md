# Life Engine Runtime API Contract (v1)

Generic pluggable AI runtime — no vertical-specific fields in core DTOs.

## RunRequest — `POST /api/runtime/runs`

| Field | Required | Notes |
|-------|----------|-------|
| `workflowId` | yes | Registered workflow id |
| `input` | yes | Stage input text |
| `correlationId` | no | Server generates `corr-{runId}` if omitted |
| `metadata` | no | Generic key/value bag |

## RunResponse

Flat fields: `runId`, `workflowId`, `correlationId`, `status`, `startedAt`, `updatedAt`, `message`, `warnings`, `metadata`.

## RunDetailResponse — `GET /api/runtime/runs/{runId}`

Canonical replay/rehydration surface. Includes flattened run fields plus:

- `agentStages` — `AgentStage` records
- `llmCalls` — `LlmCallRecord` entries
- `events` — `RuntimeEvent` list (same shape as SSE payloads)
- `warnings`
- `terminalError` (null when `status` is `CANCELLED`)

## Workflows — `GET /api/runtime/workflows`

Lists registered `WorkflowDefinition` entries.

## EventType (canonical)

`RUN_*`, `STAGE_*`, `AGENT_*`, `TOOL_*`, `LLM_CALL_*`, `WARNING_RECORDED`.

Legacy aliases removed from emission (`RUN_COMPLETED` → `RUN_SUCCEEDED`, `LLM_REQUESTED` → `LLM_CALL_STARTED`, etc.).

## SSE — `GET /api/runtime/runs/{runId}/stream`

Canonical SSE path (replay + live). Legacy alias: `GET .../events`.

Requires `Authorization: Bearer <life-engine JWT>` when security is enabled. Angular cockpit should use **fetch** + `ReadableStream` (not headerless `EventSource`).

Named events use canonical `EventType` wire names. Payload uses `RuntimeEventResponse` with top-level `workflowId`, `correlationId`, `stageId`, `agentId`, `toolId`, and JSON-safe `payload` map.

## Registry inspection

| Method | Path | Auth (when enabled) |
|--------|------|---------------------|
| GET | `/api/runtime/health` | public |
| GET | `/api/runtime/workflows` | `RUNTIME_VIEWER` |
| GET | `/api/runtime/agents` | `RUNTIME_VIEWER` |
| GET | `/api/runtime/tools` | `RUNTIME_VIEWER` |

## Security (life-engine JWT)

Uses the same HS256 secret as platform auth (`lifeengine.security.jwt.secret`). Permission codes in JWT `authorities` claim:

| Authority | Grants |
|-----------|--------|
| `RUNTIME_VIEWER` | GET `/api/runtime/**` |
| `RUNTIME_OPERATOR` | POST runs, POST cancel |
| `RUNTIME_ADMIN` | Actuator metrics/info (not public health/prometheus scrape) |

Disable for local integration tests: `lifeengine.runtime.security.enabled=false`.

Platform RBAC seed: `docs/operations/runtime-rbac-seed.sql`.
