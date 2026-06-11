# life-engine-runtime

[![CI](https://img.shields.io/badge/CI-Java%2021%20%C2%B7%20Maven%20%C2%B7%20Temurin-success)](.github/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/java-21-orange)
![Spring Boot 3.4](https://img.shields.io/badge/spring--boot-3.4.x-brightgreen)
![Reactor WebFlux](https://img.shields.io/badge/reactor-webflux-blue)
![Status](https://img.shields.io/badge/status-portfolio--ready-informational)

Reactive execution substrate for the Life Engine platform — a generic agent / workflow
runtime built on Spring WebFlux, R2DBC and Reactor. Designed to be the **same orchestration
core** behind multiple verticals (CryptoBot today, Dev Agent next, future agent runtimes
after that) without absorbing any of their business concerns.

---

## What this runtime IS

- **Workflow execution** — definition-driven (`WorkflowDefinition` → ordered `WorkflowStage`s)
  with deterministic event emission, per-stage timeouts and operator-cancel support.
- **Agent orchestration** — pluggable `AgentExecutor` and `ToolExecutor` registries with
  loud failures on unknown agent / tool ids (no silent fallback).
- **Event spine** — append-only `RuntimeEvent` log (`RUN_*`, `STAGE_*`, `AGENT_*`, `TOOL_*`,
  `LLM_CALL_*`, `WARNING_RECORDED`) persisted per run, fanned out live over an in-process
  replay sink.
- **SSE / replay surface** — single `Flux<ServerSentEvent>` per run that merges persisted
  history with live events, deduplicates by `eventId`, and completes on the first terminal
  event.
- **LLM call lifecycle** — OpenAI-compatible (vLLM, llama.cpp server, OpenAI proper) chat
  completions with retry-on-transient, per-call records, prompt/response redaction, and
  full `LLM_CALL_STARTED` / `LLM_CALL_SUCCEEDED` / `LLM_CALL_FAILED` events.
- **Replay / persistence** — PostgreSQL via R2DBC for hot reads, JDBC + Flyway for schema
  evolution; the run / events / stages / LLM-calls schema is the rehydration substrate.
- **Runtime observability** — Micrometer + Prometheus metrics, Micrometer Tracing (OTLP),
  per-request / per-run correlation IDs threaded through MDC and Reactor `Context`.

## What this runtime is NOT

This is the platform's **runtime boundary**. By design it does **not** contain:

- ❌ An identity provider — auth is owned by `life-engine-auth` (separate repo, separate
  DB, separate ownership). This runtime only **consumes** JWTs.
- ❌ A crypto / trading vertical — CryptoBot business logic lives in `cryptobot-service`.
  The `ext.cryptomarketreview` package in this repo is a *registered runtime module*
  (`RuntimeModule` SPI) that calls the cryptobot HTTP API; it does not encode trading,
  pricing, or signal logic.
- ❌ A RAG / vector / memory service — the runtime has no embeddings store, no vector DB,
  no retrieval layer.
- ❌ A Dev-Agent monolith — Dev Agent will mount on this runtime via the same
  `RuntimeModule` SPI used by CryptoBot.
- ❌ A frontend — the Angular cockpit lives in `life-engine-runtime-ui`. A minimal static
  `index.html` is shipped for smoke testing only.
- ❌ A trading engine — order routing, position management, PnL: all out of scope.

If a contribution would add any of those, it belongs in another repo.

---

## Workflow execution lifecycle

```
HTTP POST /api/runtime/runs          ──┐
                                       ├─► RunService.startRun (Mono on boundedElastic)
                                       │     ├─ store.saveRun  (QUEUED → RUNNING)
                                       │     ├─ WorkflowRouter.start ─► WorkflowExecutor
                                       │     │      DefinitionDrivenWorkflowExecutor:
                                       │     │        emit RUN_STARTED
                                       │     │        for each WorkflowStage in order:
                                       │     │          emit STAGE_STARTED
                                       │     │          (AGENT) ─► AgentExecutor.execute(...)
                                       │     │                       optional LlmAgentSupport.callLlm
                                       │     │                         emit LLM_CALL_STARTED
                                       │     │                         await response (with retry)
                                       │     │                         emit LLM_CALL_SUCCEEDED|FAILED
                                       │     │                         appendLlmCallRecord
                                       │     │          (TOOL)  ─► ToolExecutor.execute(...)
                                       │     │          publishOn(boundedElastic)
                                       │     │          emit STAGE_SUCCEEDED|FAILED
                                       │     │          recordStage (AgentStageRecord)
                                       │     │        emit RUN_SUCCEEDED|FAILED (terminal)
                                       │     │        store.saveRun (terminal status)
                                       │     └─ return Run
                                       └─► HTTP 201 RunResponse
```

Cancellation: `POST /api/runtime/runs/{runId}/cancel` flips an `AtomicBoolean`, disposes the
active subscription, persists `RUN_CANCELLED` and updates run status. In-flight LLM HTTP
calls may still complete (documented in the cancel response metadata).

## Event spine

| Type | Emitted on | Terminal |
|------|-----------|----------|
| `RUN_STARTED` | workflow scheduling begins | no |
| `RUN_SUCCEEDED` | every stage completes | **yes** |
| `RUN_FAILED` | any uncancelled failure | **yes** |
| `RUN_CANCELLED` | operator cancel | **yes** |
| `STAGE_STARTED` / `STAGE_SUCCEEDED` / `STAGE_FAILED` | per workflow stage | no |
| `AGENT_STARTED` / `AGENT_SUCCEEDED` / `AGENT_FAILED` | agent execution | no |
| `TOOL_STARTED` / `TOOL_SUCCEEDED` / `TOOL_FAILED` | tool execution | no |
| `LLM_CALL_STARTED` / `LLM_CALL_SUCCEEDED` / `LLM_CALL_FAILED` | per LLM HTTP call | no |
| `WARNING_RECORDED` | retry / soft-failure notes | no |

Events are append-only. Persistence keys: `runtime_event(event_id PK, run_id FK, type,
occurred_at, source, attributes JSONB, terminal, seq BIGSERIAL)`. Replay is ordered by
`seq` (insertion order).

## SSE / replay

`GET /api/runtime/runs/{runId}/stream` (canonical) and `/events` (deprecated alias) return
a `text/event-stream` that:

1. Verifies the run exists (404 if not).
2. **Subscribes** to the live `RunEventPublisher` (in-process replay sink, last 256 events).
3. **Replays** the persisted `eventsFor(runId)` history.
4. **Deduplicates** by `eventId` (a `seen` set survives the replay-then-live join).
5. Emits a 15-second keepalive comment frame so idle browsers / proxies do not reap the
   connection.
6. Completes on the first event flagged `terminal=true`.

The whole SSE pipeline is anchored to `Schedulers.boundedElastic` for any RunStore touch
(handshake on a Netty `reactor-http-*` event loop never blocks). See the **Reactive
architecture** section.

## LLM orchestration

`OpenAiCompatibleLlmClient` speaks the OpenAI `POST /v1/chat/completions` contract — works
unchanged against vLLM, llama.cpp server, LM Studio, or OpenAI proper.

- Single provider, single base URL per process — no silent fallback chain.
- Per-call records (`runtime_llm_call_record`) with prompt + response previews,
  duration, parse error (when JSON-strict parsing fails), and provider metadata.
- Transient HTTP failures (5xx, timeouts) retry with configurable max attempts + backoff
  (`runtime.llm.retry.*`); retry emissions are recorded as `WARNING_RECORDED` events.
- `SecretRedactor` masks anything that smells like a key (`sk-...`, `Bearer ...`,
  long high-entropy tokens) before any event attribute is persisted or emitted.

## Runtime / auth separation

This runtime is a **JWT consumer**. The `life-engine-auth` repo is the identity provider.
Hard boundaries:

| Concern | Owner |
|---------|-------|
| User registration, password storage, MFA | `life-engine-auth` |
| Refresh tokens, session management | `life-engine-auth` |
| `auth_user`, `auth_role`, `auth_permission`, `auth_role_permission` tables | `life-engine-auth` |
| JWT signing key (`lifeengine.security.jwt.secret`) | `life-engine-auth` issues, runtime verifies |
| `RUNTIME_VIEWER` / `RUNTIME_OPERATOR` / `RUNTIME_ADMIN` authorities | seeded in auth DB, consumed here |
| Run history, events, stages, LLM calls | **this runtime** (DB `life_engine_runtime`) |
| Workflow registry / agent registry / tool registry | **this runtime** |

The runtime's only auth surface is a resource-server-style filter
(`RuntimeJwtAuthenticationWebFilter`, `Order = HIGHEST_PRECEDENCE + 10`) that parses the
bearer JWT, materialises a `RuntimePrincipal`, and stores the raw token in the
`Authentication.credentials` slot so outbound `WebClient`s (e.g. `cryptobotWebClient`) can
propagate the caller's identity downstream. No user table, no password hashing, no token
mint API lives in this repo. The Flyway migration `V1__runtime_run_history.sql` contains
zero auth columns.

A **Phase-1 bridge** (`lifeengine.runtime.security.derive-runtime-authorities-from-role=true`,
default) mints `RUNTIME_*` authorities from the JWT `role` claim until
`life-engine-auth`'s Flyway migration seeds them through `auth_role_permission`. Set the
flag to `false` once that lands. See `docs/operations/runtime-rbac-seed.sql` for the auth-DB
seed template.

## Reactive architecture

The runtime is fully reactive (`WebFlux + Project Reactor`). The blocking contract is the
single most operationally important property in this codebase:

- **`R2dbcRunStore`** is the only place `.block(BLOCK_TIMEOUT)` is called. It implements the
  synchronous `RunStore` SPI and **must** be invoked from a thread that tolerates blocking
  (`Schedulers.boundedElastic()` or another worker pool). Every call site already hops onto
  `boundedElastic` before touching the store.
- **`RunService`** wraps every store interaction in `Mono.fromCallable(...)
  .subscribeOn(Schedulers.boundedElastic())`.
- **`DefinitionDrivenWorkflowExecutor`** subscribes the per-run pipeline on `boundedElastic`,
  inserts a `publishOn(boundedElastic)` hop after every `WebClient` boundary (LLM call,
  cryptobot tool call), and runs `completeRun` / `failRun` via dedicated bounded-elastic
  monos so error and success paths can never land terminal store writes on a Netty
  `reactor-http-*` thread.
- **`WorkflowRunContext`** exposes both synchronous (`emit`, `recordStage`,
  `appendLlmCallRecord`) and reactive (`emitMono`, `recordStageMono`,
  `appendLlmCallRecordMono`) variants. Reactive callbacks chained directly off a
  `WebClient` response **must** use the `*Mono` variants — documented inline.
- **`LlmAgentSupport.callLlm`** anchors `publishOn(Schedulers.boundedElastic())` immediately
  after `llmClient.chatCompletion(...)`. Every downstream `doOnSuccess` /
  `onErrorResume` runs on bounded-elastic.
- **`RunEventStreamService`** defers store reads under `Flux.defer(...)` +
  `subscribeOn(boundedElastic)` so the SSE handshake (Netty event-loop) never blocks.

Two regression suites lock this contract in:

- `EventLoopBlockingSafetyTest` — drives an LLM failure on `Schedulers.parallel()` (a
  `NonBlocking` scheduler with the same Reactor contract as `reactor-http-epoll-*`) and
  asserts no `RunStore` method was ever invoked on a non-blocking thread.
- `SseStreamBlockingSafetyTest` — subscribes the SSE flux from `Schedulers.parallel()` and
  asserts the same invariant for the SSE replay path.

Both use a `ThreadAuditingRunStore` decorator that flags any call where
`Schedulers.isInNonBlockingThread()` is true — the same predicate the R2DBC blocking
detector uses inside `R2dbcRunStore.block(...)`.

## Tech stack

| Layer | Choice |
|-------|--------|
| Language | Java 21 |
| Framework | Spring Boot 3.4.x + Spring WebFlux |
| Reactive runtime | Project Reactor (`reactor-core`, `reactor-test`) |
| Persistence (hot path) | Spring Data R2DBC + `r2dbc-postgresql` |
| Persistence (migrations) | Flyway (JDBC) over PostgreSQL 16 |
| Security | Spring Security (resource-server style) + JJWT 0.12 (HS512) |
| LLM client | `WebClient` against OpenAI-compatible `/v1/chat/completions` |
| Observability | Micrometer + `micrometer-registry-prometheus` + Micrometer Tracing OTLP bridge |
| Build | Maven 3.9+ |
| Tests | JUnit 5, `WebTestClient`, MockWebServer (OkHttp), Testcontainers |

---

## Local development

Prerequisites: Java 21 (Temurin recommended), Maven 3.9+, Docker (optional — only for
PostgreSQL or the R2DBC integration test).

### Start with PostgreSQL (default)

```bash
docker run -d --name life-engine-runtime-pg \
  -e POSTGRES_USER=life \
  -e POSTGRES_PASSWORD=life \
  -e POSTGRES_DB=life_engine_runtime \
  -p 5433:5432 \
  postgres:16-alpine

mvn -ntp spring-boot:run
```

Default port: **8090**. Flyway runs `V1__runtime_run_history.sql` on first boot.

### Start without PostgreSQL (volatile in-memory store)

```bash
LIFEENGINE_RUNTIME_PERSISTENCE_TYPE=inmem \
SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration \
  mvn -ntp spring-boot:run
```

Runs are lost on restart. Useful for cockpit smoke tests and demos.

### Configuration knobs

All defaults are env-overridable. Key properties:

| Property | Default | Env override |
|----------|---------|--------------|
| `spring.r2dbc.url` | `r2dbc:postgresql://localhost:5433/life_engine_runtime` | `SPRING_R2DBC_URL` |
| `spring.r2dbc.username` / `password` | `life` / `life` | `SPRING_R2DBC_USERNAME` / `SPRING_R2DBC_PASSWORD` |
| `spring.flyway.url` | `jdbc:postgresql://localhost:5433/life_engine_runtime` | `SPRING_FLYWAY_URL` |
| `lifeengine.runtime.persistence.type` | `r2dbc` | `LIFEENGINE_RUNTIME_PERSISTENCE_TYPE` (`r2dbc` \| `inmem`) |
| `lifeengine.runtime.security.enabled` | `true` | `RUNTIME_SECURITY_ENABLED` |
| `lifeengine.runtime.security.derive-runtime-authorities-from-role` | `true` (Phase-1 bridge) | `RUNTIME_DERIVE_AUTHORITIES_FROM_ROLE` |
| `lifeengine.security.jwt.secret` | local-dev placeholder (≥32 bytes) | `JWT_SECRET` |
| `runtime.llm.base-url` | `http://localhost:8000` | property override |
| `runtime.llm.model` | `Qwen/Qwen2.5-Coder-3B-Instruct` | property override |
| `runtime.llm.api-key` | `local-dev` | property override |
| `runtime.llm.timeout` | `30s` | property override |
| `server.port` | `8090` | `SERVER_PORT` |
| `management.otlp.tracing.endpoint` | `http://localhost:4318/v1/traces` | `OTEL_EXPORTER_OTLP_ENDPOINT` |

The local-dev JWT secret intentionally matches `life-engine-auth`'s local-default so auth-
issued tokens validate at this runtime without exporting `JWT_SECRET`. **Override in every
non-local environment.**

### Cockpit UI

The Angular cockpit lives in [`life-engine-runtime-ui`](../life-engine-runtime-ui/README.md)
(`npm start` on port `4202`). A minimal static fallback is served from
`http://localhost:8090/` for smoke tests.

---

## Running tests

### Default lane (hermetic, no Docker required)

```bash
mvn -B -ntp test
```

`application-test.yml` forces `persistence.type=inmem` and excludes the R2DBC / Flyway /
JDBC autoconfigs, so unit + WebFlux suites need neither PostgreSQL nor a vLLM server (LLM
calls use `MockWebServer`).

### Integration tests (Docker required)

`R2dbcRunStoreTest` opts back into the full persistence stack via a
`@DynamicPropertySource` that points Spring at an ephemeral `postgres:16-alpine` started by
Testcontainers. The test is included in the default lane: when Docker is reachable it runs
end-to-end; when it is not, Testcontainers fails fast and the lane goes red. There is no
silent skip — opting out of integration coverage requires a deliberate workflow change.

### Suite shape (current)

```
io.lifeengine.runtime
├── agents          — registry, JSON-strict parsing, retry semantics
├── api             — REST + WebFlux contract tests (RunResponse, RunDetail)
├── core            — R2DBC integration test (Testcontainers)
├── events          — SSE blocking-safety regression
├── ext.*           — runtime-module integration (CryptoMarketReview)
├── extension       — RuntimeRegistry + pluggable-workflow WebFlux
├── llm             — OpenAI-compatible client (MockWebServer)
├── prompts         — prompt template registry
├── security        — JWT WebFlux end-to-end
└── workflow        — executor, router, blocking-safety, timeout, happy-path
```

---

## Runtime APIs

| Method | Path | Auth (when enabled) | Description |
|--------|------|---------------------|-------------|
| GET | `/actuator/health` | public | Liveness + custom `runtime` indicator |
| GET | `/actuator/prometheus` | public | Prometheus scrape |
| GET | `/actuator/metrics/**` | `RUNTIME_ADMIN` | Micrometer metrics |
| GET | `/actuator/**` (other) | `RUNTIME_ADMIN` | Actuator catalogue |
| GET | `/api/runtime/health` | public | Compatibility alias for cockpit |
| POST | `/api/runtime/runs` | `RUNTIME_OPERATOR` | Start a workflow run |
| GET | `/api/runtime/runs/{runId}` | `RUNTIME_VIEWER` | Run detail (run + stages + LLM calls + events) |
| GET | `/api/runtime/runs/{runId}/stream` | `RUNTIME_VIEWER` | SSE: replay + live (canonical) |
| GET | `/api/runtime/runs/{runId}/events` | `RUNTIME_VIEWER` | Deprecated alias for `/stream` |
| POST | `/api/runtime/runs/{runId}/cancel` | `RUNTIME_OPERATOR` | Cancel a non-terminal run |
| GET | `/api/runtime/workflows` | `RUNTIME_VIEWER` | Registered `WorkflowDefinition`s |
| GET | `/api/runtime/agents` | `RUNTIME_VIEWER` | Registered `AgentExecutor`s |
| GET | `/api/runtime/tools` | `RUNTIME_VIEWER` | Registered `ToolExecutor`s |

### Start-run payload

```json
{
  "workflowId": "demo.llm.workflow",
  "input": "text to summarize and classify",
  "correlationId": "optional-client-supplied-id",
  "metadata": { "freeform": "key-value bag" }
}
```

Built-in demo workflows:

- `demo.llm.workflow` — `SummarizerAgent` → vLLM → `ClassifierAgent` → vLLM.
- `demo.no-llm.workflow` — explicit no-stage fake demo (no LLM, no agents).

Vertical workflows (e.g. `crypto.market-review.v1`) register themselves through the
`RuntimeModule` SPI at boot.

### SSE notes

- `EventSource` cannot set headers; the JWT filter accepts `?access_token=...` for the SSE
  GET endpoints as a documented fallback. Cockpit clients should prefer
  `fetch + ReadableStream` with `Authorization: Bearer` headers.
- Named events use the canonical `EventType` wire names (see **Event spine** table).
- Payload shape: `RuntimeEventResponse` with `workflowId`, `correlationId`, `stageId`,
  `agentId`, `toolId`, and a JSON-safe `payload` map.

Full contract: [`docs/api/runtime-api-contract.md`](docs/api/runtime-api-contract.md).

---

## Observability

The runtime ships a coherent operator story out of the box — no overbuilt dashboards, just
the surfaces that make a reactive system debuggable in production.

### Metrics (Micrometer + Prometheus)

Exposed at `GET /actuator/prometheus`. Runtime-specific meters:

| Meter | Type | Tags | Emitted from |
|-------|------|------|--------------|
| `runtime.runs.started` | counter | `workflowId` | `RunService.startRun` |
| `runtime.runs.terminal` | counter | `workflowId`, `status` | terminal transition |
| `runtime.run.duration` | timer | `workflowId`, `status` | `RunService.startRun` sample |
| `runtime.stages` | counter | `stageType`, `status` | per stage outcome |
| `runtime.agents` | counter | `agentId`, `status` | per agent outcome |
| `runtime.tools` | counter | `toolId`, `status` | per tool outcome |
| `runtime.llm.calls` | counter | `model`, `status` | `OpenAiCompatibleLlmClient` |
| `runtime.llm.failures` | counter | `model` | LLM transport / HTTP error |
| `runtime.sse.streams` | counter | — | SSE open |

All meters carry the `application=life-engine-runtime` tag.

### Tracing (Micrometer Tracing → OTLP)

`RuntimeObservation` opens two named spans per run:

- `runtime.run.execute` — tags `workflow.id`, `run.id`, `correlation.id`.
- `runtime.stage.execute` — tags `workflow.id`, `run.id`, `stage.id`, `stage.type`.

Exported via the OpenTelemetry OTLP HTTP exporter (`management.otlp.tracing.endpoint`,
default `http://localhost:4318/v1/traces`). Sampling probability is configurable
(`OTEL_TRACES_SAMPLER_PROBABILITY`).

### Correlation / log MDC

`RequestCorrelationWebFilter` (`Order = HIGHEST_PRECEDENCE`):

1. Reads or generates `X-Request-Id` and `X-Correlation-Id` per request.
2. Echoes both back as response headers (CORS-exposed).
3. Extracts `runId` from `/api/runtime/runs/{runId}/...` paths.
4. Writes `requestId`, `correlationId`, `runId`, `workflowId` into both Reactor `Context`
   and SLF4J MDC.

Log pattern (`application.yml`):

```
%5p [life-engine-runtime,<requestId>,<correlationId>,<runId>,<workflowId>] ...
```

So every log line in a run carries the same correlation key as the SSE / replay payloads.

### Health

`GET /actuator/health` is public; it includes the runtime indicator:

```json
{
  "status": "UP",
  "components": {
    "runtime": {
      "status": "UP",
      "details": {
        "component": "life-engine-runtime",
        "store": "R2dbcRunStore",
        "persistence": "r2dbc-postgres"
      }
    }
  }
}
```

Spring Boot's own `db` / `r2dbc` indicators cover connectivity probing. `GET /actuator/health`
returns full details only when authorized (`show-details: when_authorized`).

### Runtime status surfaces

- `GET /api/runtime/runs/{runId}` — full rehydration: run, stages, LLM calls, events,
  warnings, terminal error.
- `GET /api/runtime/runs/{runId}/stream` — live event tap (see **SSE / replay**).
- `GET /api/runtime/workflows|agents|tools` — registry introspection (operator UI hook).

---

## Security baseline

| Concern | Posture |
|---------|---------|
| Transport | TLS terminated upstream (deployment concern, not in this repo) |
| Authentication | Resource-server-style JWT (HS512, JJWT 0.12) verifying tokens issued by `life-engine-auth` |
| Authorization | Spring Security `authorizeExchange` + `RUNTIME_*` authority gates (see API table) |
| CORS | `CorsWebFilter` registered at `HIGHEST_PRECEDENCE` so OPTIONS preflights short-circuit before the JWT filter; allowed origin patterns scoped to local dev (`localhost:*`, `127.0.0.1:*`) |
| Actuator exposure | Only `health`, `info`, `prometheus`, `metrics` exposed; metrics + non-health actuators gated by `RUNTIME_ADMIN`; `prometheus` open for scraping |
| Error responses | JSON-shaped `ApiError(code, message)` from a typed `ExceptionHandler` — no stack-trace leakage |
| Dev-only endpoints | None enabled by default; static cockpit fallback is read-only HTML |
| Profile behaviour | `test` profile disables security and pins `inmem` persistence; production defaults require a JWT |
| Secret handling | `SecretRedactor` redacts before any event attribute / log line is emitted |
| Outbound auth propagation | `cryptobotWebClient` re-attaches the inbound bearer to downstream calls via an `ExchangeFilterFunction` — runtime acts on behalf of the caller |

Boot-time invariant: `RuntimeJwtService` refuses to start if the HMAC secret is shorter
than 32 UTF-8 bytes (`IllegalStateException` at bean construction). This catches the
"empty default in production" foot-gun before any request lands.

---

## Current limitations

- **`RunStore` SPI is synchronous.** The R2DBC implementation reactively assembles each
  call and then `.block(BLOCK_TIMEOUT)`s. Two regression tests
  (`EventLoopBlockingSafetyTest`, `SseStreamBlockingSafetyTest`) prove no call ever lands
  on a `NonBlocking` thread, but the SPI itself remains a future refactor: rewriting
  `RunStore` to `Mono` / `Flux` is tracked but explicitly **out of scope** for this pass.
- **`ext.cryptomarketreview` lives in this jar.** It is wired through the `RuntimeModule`
  SPI (so it is not architecturally coupled), but it is physically in the runtime jar so it
  cannot be deployed as a separate module today. The clean separation is: move
  `ext.cryptomarketreview.*` (and its tests) into a `life-engine-cryptobot-runtime-module`
  jar that depends on runtime as a library and is `@ConditionalOnProperty`-loaded.
- **Phase-1 RBAC bridge.** Until `life-engine-auth` seeds `RUNTIME_*` permissions through
  `auth_role_permission`, the runtime mints them locally from the JWT `role` claim
  (`lifeengine.runtime.security.derive-runtime-authorities-from-role=true`). Flip the flag
  to `false` once the auth migration ships.
- **In-process replay sink.** `RunEventPublisher` uses an in-JVM `Sinks.many().replay()`
  with a 256-event limit. Single-instance only; horizontal scale-out needs a distributed
  bus (Redis Streams / NATS / Kafka) — explicitly out of scope here.
- **No deployment manifests yet.** Dockerfile + Kubernetes manifests are intentionally not
  in this repo; they are owned by the platform deployment repo (TBD).
- **No CI gate on integration tests when Docker is unavailable.** Locally and on
  GitHub-hosted runners Docker is present, so this is not an effective gap; document if
  using a runner without Docker.

## Phase 1 — Business Chat RAG (GREEN)

**Tag:** `phase-1-rag-green`

Workflow `business-chat.reply.v1` consume `businessContext.retrievedChunks` (inyectados por Business Chat Service, **no** por este runtime). Devuelve `sources` opcionales en el output del stage `business-reply`.

Runtime **no** llama a RAG. Smokes: `../../scripts/smoke-ai-stack.sh` · Guía: [`../../scripts/PHASE-1-RAG.md`](../../scripts/PHASE-1-RAG.md)

---

## Roadmap

- [ ] Extract `ext.cryptomarketreview` into a standalone `life-engine-cryptobot-runtime-module` jar.
- [ ] Rewrite `RunStore` to a reactive SPI (`Mono<Run> findRun`, `Mono<Void> appendEvent`, ...);
      delete the `.block(BLOCK_TIMEOUT)` block in `R2dbcRunStore`.
- [ ] Replace the `RunEventPublisher` in-JVM sink with a distributed event bus for
      horizontal scale-out.
- [ ] Drop the Phase-1 RBAC bridge once `life-engine-auth` seeds `RUNTIME_*` permissions.
- [ ] Deployment baseline (Dockerfile + minimal Kubernetes manifests) — separate repo /
      separate pass.
- [ ] Dev Agent runtime module — first non-crypto consumer of the `RuntimeModule` SPI.

---

## License

Internal portfolio project. See repository owner for licensing terms.
