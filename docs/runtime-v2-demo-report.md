# Runtime V2 Multi-Model — Demo Report

**Fecha:** 2026-06-18  
**Branch:** `phase-1-rag-green`  
**Commit:** `c0fb5b2`  
**Runtime PID:** 2528142 (iniciado 2026-06-18 00:06 con código V2)

---

## 1. Git Status

```
On branch phase-1-rag-green
Your branch is ahead of 'origin/phase-1-rag-green' by 9 commits.
nothing to commit, working tree clean
```

### Commits V2 (más recientes primero)

| SHA | Descripción |
|-----|-------------|
| `c0fb5b2` | Fase 5: EvaluatorAgent + Crypto agents → `smartLlmClient` |
| `e43ad4e` | Fase 4: DevAnswerAgent → `codingLlmClient` |
| `261697d` | Fase 1-3 + I2: BusinessReplyAgent → `chatLlmClient`, audit fix model_role snake_case |

---

## 2. Servicios Activos

| Servicio | Puerto | Modelo | Estado |
|----------|--------|--------|--------|
| life-engine-runtime | 8090 | — | ✅ UP (`llmReachable=true`, store=R2dbcRunStore) |
| vLLM (default/coding) | 8000 | `Qwen/Qwen2.5-Coder-3B-Instruct` | ✅ UP |
| Ollama (chat) | 11434 | `gemma3:4b` | ✅ UP |
| vLLM (smart) | 8002 | `DeepSeek-R1-Distill-Qwen-14B` | ❌ OFFLINE |
| PostgreSQL | 5433 | — | ✅ UP |
| cryptobot-service | 8091 | — | ✅ UP |
| business-chat-service | 8092 | — | ✅ UP |
| dev-agent-service | 8093 | — | ✅ UP |
| rag-service | 8095 | — | ✅ UP |

---

## 3. Test Suite

```
Total:   352 tests
PASS:    351
FAIL:    1  (StrictAgentJsonBusinessTest.promptIntentsMatchValidationSet — pre-existing,
             confirmado con git stash antes de cambios V2)
```

Tests afectados Fases 3/4/5: **58/58 PASS**

---

## 4. Smokes Live

### Smoke 1 — Business Chat (`business-chat.reply.v1`)

```
runId:       8493603e-822e-4bce-8c1c-ab04c59fc2f6
correlationId: smoke-v2-chat
status:      SUCCEEDED
startedAt:   2026-06-18T03:07:23.229653Z
```

| agentId | model_role | modelo | durationMs | status |
|---------|-----------|--------|-----------|--------|
| `business-context-agent` | `null` (default, no migrado) | `Qwen/Qwen2.5-Coder-3B-Instruct` | 585 | ✅ SUCCEEDED |
| `business-reply-agent` | **`chat`** | **`gemma3:4b`** | **2974** | ✅ SUCCEEDED |

**Resultado:** GO ✅ — `model_role=chat` presente en `LLM_CALL_SUCCEEDED`. Agente no migrado usa default correctamente.

---

### Smoke 2 — Dev Knowledge Answer (`dev.knowledge-answer.v1`)

```
runId:       5cacb1a7-6fff-4635-9829-c392d71273c0
correlationId: smoke-v2-coding
status:      SUCCEEDED
startedAt:   2026-06-18T03:~
```

| agentId | model_role | modelo | durationMs | status |
|---------|-----------|--------|-----------|--------|
| `dev-context-agent` | `null` (default, no migrado) | — (sin LLM) | — | ✅ SUCCEEDED |
| `dev-answer-agent` | **`coding`** | **`Qwen/Qwen2.5-Coder-3B-Instruct`** | **783** | ✅ SUCCEEDED |

**Resultado:** GO ✅ — `model_role=coding` presente en `LLM_CALL_SUCCEEDED`.

---

### Smoke 3 — Crypto Market Review (`crypto.market-review.v1`)

```
runId:       c54b4c31-8847-4880-8fe1-668facc214e8
correlationId: smoke-v2-smart
status:      FAILED
```

| agentId | error | status |
|---------|-------|--------|
| `load-crypto-market-context-agent` | `block()/blockFirst()/blockLast() are blocking, which is not supported in thread reactor-http-epoll-2` | ❌ FAILED |

**Bloqueos:**

1. **Bug pre-existente** en `LoadCryptoMarketContextAgent` — `.block()` en fan-out de cryptobot tools llamado desde Netty event loop. No introducido por Fases 3/4/5 (diff confirma 0 cambios a ese archivo en commits V2). Los agentes SMART nunca se alcanzan en live.
2. **vLLM-8002 OFFLINE** — `DeepSeek-R1-Distill-Qwen-14B` no cargado.

**Evidencia SMART vía Integration Tests** (fresh Spring context, MockWebServer):

| agentId | model_role | test | resultado |
|---------|-----------|------|-----------|
| `crypto-market-analyst-agent` | **`smart`** | `cryptoAgents_emitModelRoleSmartInLlmSucceededEvents` | ✅ PASS |
| `crypto-risk-review-agent` | **`smart`** | `cryptoAgents_emitModelRoleSmartInLlmSucceededEvents` | ✅ PASS |
| `crypto-final-summary-agent` | **`smart`** | `cryptoAgents_emitModelRoleSmartInLlmSucceededEvents` | ✅ PASS |
| `evaluator-agent` | **`smart`** | `evaluatorAgent_injectsSmartLlmClient` (wiring) | ✅ PASS |

**Resultado:** NO GO live — código correcto y verificado por tests, live bloqueado por causas pre-V2.

---

## 5. Mapa de Agentes V2

| agentId | Fase | model_role | Modelo (config) | Evidencia live | Evidencia test |
|---------|------|-----------|-----------------|----------------|----------------|
| `business-context-agent` | no migrado | `null` | Qwen/Qwen2.5-Coder-3B | ✅ smoke 1 | ✅ |
| `business-reply-agent` | Fase 3 | **`chat`** | gemma3:4b (Ollama) | ✅ smoke 1 | ✅ |
| `dev-context-agent` | no migrado | `null` | — (sin LLM) | ✅ smoke 2 | ✅ |
| `dev-answer-agent` | Fase 4 | **`coding`** | Qwen/Qwen2.5-Coder-3B | ✅ smoke 2 | ✅ |
| `parse-crypto-intent-agent` | no migrado | `null` | Qwen/Qwen2.5-Coder-3B | — | ✅ |
| `load-crypto-market-context-agent` | no migrado | — (sin LLM) | — | ❌ bug block() | ✅ |
| `crypto-market-analyst-agent` | Fase 5 | **`smart`** | DeepSeek-R1-14B | pendiente | ✅ |
| `crypto-risk-review-agent` | Fase 5 | **`smart`** | DeepSeek-R1-14B | pendiente | ✅ |
| `crypto-final-summary-agent` | Fase 5 | **`smart`** | DeepSeek-R1-14B | pendiente | ✅ |
| `evaluator-agent` | Fase 5 | **`smart`** | DeepSeek-R1-14B | pendiente | ✅ |

---

## 6. Bloqueos Pendientes (pre-V2, fuera de scope Fases 3/4/5)

| # | Componente | Problema | Impacto |
|---|-----------|----------|---------|
| 1 | `LoadCryptoMarketContextAgent` | `.block()` en Netty epoll thread — fan-out de cryptobot tools | Smoke 3 live FAILED |
| 2 | vLLM-8002 | `DeepSeek-R1-Distill-Qwen-14B` no levantado | Smoke 3 live FAILED aunque se fix bloqueo 1 |
| 3 | `StrictAgentJsonBusinessTest` | Espera 8 intents, tiene 13 (guardrail intents añadidos) | Pre-existing, no V2 |

Para smoke 3 live: resolver bloqueo 1 (fix reactivo) + levantar vLLM-8002.

---

## 7. Veredicto

| Workflow | model_role | Demo live |
|----------|-----------|-----------|
| Business Chat → CHAT | `chat` / gemma3:4b | **GO** ✅ |
| Dev Knowledge Answer → CODING | `coding` / Qwen2.5-Coder-3B | **GO** ✅ |
| Crypto Market Review → SMART | `smart` / DeepSeek-R1 | **NO GO live** ⚠️ (código correcto, 2 bloqueos pre-V2) |

### GO para Runtime V2 Demo (CHAT + CODING)

El routing multi-modelo funciona en producción para los dos primeros flujos:
- `business-reply-agent` escribe al cliente con `gemma3:4b` (modelo conversacional).
- `dev-answer-agent` responde preguntas técnicas con `Qwen2.5-Coder-3B` (modelo coding).
- Eventos `LLM_CALL_SUCCEEDED` contienen `model_role` correctamente en snake_case y lowercase.
- Agentes no migrados usan el bean `@Primary` por defecto — sin regresión.

### Pendiente para GO SMART

1. Fix reactivo en `LoadCryptoMarketContextAgent` (no scope V2).
2. Levantar `DeepSeek-R1-Distill-Qwen-14B` en `localhost:8002`.
