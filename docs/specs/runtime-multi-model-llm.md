# Spec: Multi-Modelo LLM en Runtime

**Estado:** Propuesto  
**Fecha:** 2026-06-17  
**Última actualización:** 2026-06-17 (Fase 0 — validación VRAM)  
**Autor:** sebdev89  
**ADR relacionado:** [ADR-runtime-multi-model-llm](../adrs/ADR-runtime-multi-model-llm.md)

---

## 1. Contexto y Motivación

Runtime hoy tiene una única capa LLM: todos los agentes inyectan `LlmClient` sin `@Qualifier` y reciben el mismo `OpenAiCompatibleLlmClient`, conectado al mismo vLLM apuntando a `Qwen/Qwen2.5-Coder-3B-Instruct` en `localhost:8000`.

Los modelos descargados permiten separar cargas por naturaleza de tarea. La viabilidad en GPU depende del presupuesto de VRAM disponible (ver Sección 8).

| Modelo | Parámetros | Formato descargado | VRAM necesaria | Fortalezas |
|--------|-----------|-------------------|----------------|------------|
| `Qwen/Qwen2.5-Coder-3B-Instruct` | 3B | BF16 via vLLM | ~8.3 GB | JSON estructurado, clasificación, latencia baja |
| `google/gemma-3-12b-it` | 12B | BF16 en host HF cache | ~24 GB (BF16) / ~8.5 GB (Q4) | Instrucción conversacional, respuestas naturales |
| `deepseek-ai/DeepSeek-R1-Distill-Qwen-14B` | 14B | BF16 en Docker volume | ~28 GB (BF16) / descartado | Razonamiento en cadena |

**Hallazgo Fase 0 (2026-06-17):** GPU RTX 5080 tiene 16 GB total. Con Qwen activo (~8.3 GB), solo quedan ~6.8 GB libres. Los modelos BF16 originales (gemma 24 GB, DeepSeek 28 GB) quedan **descartados para esta GPU** en formato completo.

---

## 2. Objetivo

Introducir 4 roles de modelo con configuración independiente, manteniendo backward compatibility total, sin cambiar comportamiento actual hasta que cada fase se active explícitamente.

---

## 3. Roles de Modelo

### 3.1 Definición de Roles

```
FAST_MODEL   — clasificación, extracción, routing, JSON pequeño (<100 tokens)
CHAT_MODEL   — respuestas customer-facing, lenguaje natural, multi-turn
SMART_MODEL  — razonamiento complejo, análisis financiero, evaluación
CODING_MODEL — code review, respuestas técnicas, dev knowledge
```

### 3.2 Asignación de Agentes por Rol

| Agente | Clase Java | Rol actual | Rol destino | Fase |
|--------|-----------|-----------|------------|------|
| `summarizer-agent` | `SummarizerAgent` | DEFAULT | FAST_MODEL | Fase 1 (implícito) |
| `classifier-agent` | `ClassifierAgent` | DEFAULT | FAST_MODEL | Fase 1 (implícito) |
| `extractor-agent` | `ExtractorAgent` | DEFAULT | FAST_MODEL | Fase 1 (implícito) |
| `lead-capture-agent` | `LeadCaptureAgent` | DEFAULT | FAST_MODEL | Fase 1 (implícito) |
| `business-context-agent` | `BusinessContextAgent` | DEFAULT | CHAT_MODEL | Fase 3 (junto con BusinessReply) |
| `business-reply-agent` | `BusinessReplyAgent` | DEFAULT | CHAT_MODEL | Fase 3 |
| `dev-code-review-agent` | `DevCodeReviewAgent` | DEFAULT | CODING_MODEL | Fase 4 |
| `dev-summary-agent` | `DevSummaryAgent` | DEFAULT | CODING_MODEL | Fase 4 |
| `dev-answer-agent` | `DevAnswerAgent` | DEFAULT | CODING_MODEL | Fase 4 |
| `evaluator-agent` | `EvaluatorAgent` | DEFAULT | SMART_MODEL | Fase 5 |
| `crypto-market-analyst-agent` | `CryptoMarketAnalystAgent` | DEFAULT | SMART_MODEL | Fase 5 |
| `crypto-risk-review-agent` | `CryptoRiskReviewAgent` | DEFAULT | SMART_MODEL | Fase 5 |
| `crypto-final-summary-agent` | `CryptoFinalSummaryAgent` | DEFAULT | SMART_MODEL | Fase 5 |

**Lógica de asignación:**
- FAST: tareas que producen JSON pequeño (<10 campos), sin razonamiento, latencia crítica
- CHAT: agentes que generan texto customer-facing que un humano lee directamente
- CODING: agentes que producen o analizan código, responden preguntas técnicas estructuradas
- SMART: agentes que necesitan encadenar inferencias, evaluar riesgo, producir análisis multi-step

---

## 4. Configuración YAML Propuesta

### 4.1 Estado actual (Fase 0 — sin cambios)

```yaml
runtime:
  llm:
    base-url: http://localhost:8000
    model: Qwen/Qwen2.5-Coder-3B-Instruct
    api-key: local-dev
    timeout: 30s
    max-tokens: 192
    temperature: 0
    retry:
      enabled: true
      max-attempts: 2
      backoff-millis: 200
```

### 4.2 Config multi-modelo propuesta (Fase 1+)

```yaml
runtime:
  llm:
    # --- Backward compat: sigue siendo el bean default (FastModel) ---
    base-url: ${VLLM_FAST_BASE_URL:http://localhost:8000}
    model: ${VLLM_FAST_MODEL:Qwen/Qwen2.5-Coder-3B-Instruct}
    api-key: ${VLLM_FAST_API_KEY:local-dev}
    timeout: 20s
    max-tokens: 256
    temperature: 0
    retry:
      enabled: true
      max-attempts: 2
      backoff-millis: 200

    # --- Nuevos roles (activados Fase 1) ---
    fast:
      base-url: ${VLLM_FAST_BASE_URL:http://localhost:8000}
      model: ${VLLM_FAST_MODEL:Qwen/Qwen2.5-Coder-3B-Instruct}
      api-key: ${VLLM_FAST_API_KEY:local-dev}
      timeout: 20s
      max-tokens: 256
      temperature: 0
      retry:
        enabled: true
        max-attempts: 2
        backoff-millis: 200

    chat:
      base-url: ${VLLM_CHAT_BASE_URL:http://localhost:8001}
      model: ${VLLM_CHAT_MODEL:google/gemma-3-12b-it}
      api-key: ${VLLM_CHAT_API_KEY:local-dev}
      timeout: 60s
      max-tokens: 512
      temperature: 0.3
      retry:
        enabled: true
        max-attempts: 1
        backoff-millis: 500

    smart:
      base-url: ${VLLM_SMART_BASE_URL:http://localhost:8002}
      model: ${VLLM_SMART_MODEL:deepseek-ai/DeepSeek-R1-Distill-Qwen-14B}
      api-key: ${VLLM_SMART_API_KEY:local-dev}
      timeout: 180s
      # R1 genera tokens <think> antes del output útil — necesita margen amplio
      max-tokens: 2048
      temperature: 0
      retry:
        enabled: false
        max-attempts: 0
        backoff-millis: 0

    coding:
      base-url: ${VLLM_CODING_BASE_URL:http://localhost:8000}
      model: ${VLLM_CODING_MODEL:Qwen/Qwen2.5-Coder-3B-Instruct}
      api-key: ${VLLM_CODING_API_KEY:local-dev}
      timeout: 90s
      max-tokens: 1024
      temperature: 0
      retry:
        enabled: true
        max-attempts: 1
        backoff-millis: 300
```

### 4.3 Variables de entorno requeridas

```bash
# FAST — vLLM en puerto 8000 (existente, sin cambio)
VLLM_FAST_BASE_URL=http://localhost:8000
VLLM_FAST_MODEL=Qwen/Qwen2.5-Coder-3B-Instruct

# CHAT — Ollama en puerto 11434 (ya instalado y corriendo)
# Opción A (validación Phase 0): gemma3:12b — requiere Qwen parado
# Opción B (producción simultánea en RTX 5080): gemma3:4b
VLLM_CHAT_BASE_URL=http://localhost:11434
VLLM_CHAT_MODEL=gemma3:12b          # o gemma3:4b para simultáneo

# CODING — comparte instancia FAST por ahora
VLLM_CODING_BASE_URL=http://localhost:8000
VLLM_CODING_MODEL=Qwen/Qwen2.5-Coder-3B-Instruct

# SMART — postergado hasta GPU ≥24 GB
# VLLM_SMART_BASE_URL=http://localhost:8002
# VLLM_SMART_MODEL=deepseek-ai/DeepSeek-R1-Distill-Qwen-14B
```

### 4.4 Stack de inferencia revisado (post Fase 0)

| Rol | Backend | Puerto | Modelo | Estado |
|-----|---------|--------|--------|--------|
| FAST | vLLM Docker | 8000 | Qwen2.5-Coder-3B | Activo |
| CHAT | **Ollama** (local process) | **11434** | gemma3:12b o gemma3:4b | Pendiente pull |
| CODING | vLLM Docker (comparte FAST) | 8000 | Qwen2.5-Coder-3B | Activo |
| SMART | Postergado | — | DeepSeek-R1-14B | Requiere GPU ≥24 GB |

**Por qué Ollama para CHAT en lugar de vLLM:**
- Ollama ya está corriendo en el host (no requiere Docker GPU runtime nuevo)
- Expone `/v1/chat/completions` OpenAI-compatible en `:11434` — `OpenAiCompatibleLlmClient` habla con él sin cambios
- Gestiona VRAM automáticamente (eviction después de `keep_alive` timeout)
- Pull de GGUF cuantizado sin necesidad de HF token

**Nota sobre simultaneidad:**
- `gemma3:12b` (8.15 GB): no cabe junto a Qwen (8.3 GB) en 16 GB GPU. Solo para uso secuencial.
- `gemma3:4b` (3.34 GB): cabe junto a Qwen. Total ~12 GB. Opción para producción simultánea en RTX 5080.
- La decisión entre 12B vs 4B se toma después de la validación de calidad en Fase 0.

**No se requiere** `docker-compose.vllm-multi.yml`. Ollama corre como proceso host.

---

## 5. Diseño de Implementación

### 5.1 Nuevas clases requeridas (no crear aún)

```
runtime/llm/
  LlmModelRole.java               — enum: FAST, CHAT, SMART, CODING
  RuntimeMultiModelProperties.java — @ConfigurationProperties(prefix="runtime.llm")
                                     con subrecords fast/chat/smart/coding
  LlmMultiClientConfiguration.java — @Configuration que produce 4 beans con @Qualifier
```

### 5.2 Modificaciones a clases existentes (no tocar aún)

| Clase | Cambio requerido | Fase |
|-------|-----------------|------|
| `RuntimeLlmProperties` | Sin cambio — sigue como bean default para backward compat | — |
| `LlmWebClientConfiguration` | Agregar beans `fastLlmWebClient`, `chatLlmWebClient`, etc. | Fase 1 |
| `RuntimeMetrics` | Agregar tag `model_role` a `recordLlmCall` / `recordLlmFailure` | Fase 2 |
| `LlmAgentSupport.callLlm` | Recibir `modelRole` para pasarlo a métricas | Fase 2 |
| `ClassifierAgent` + 3 core agents | `@Qualifier("fastLlmClient")` | Fase 3+ implícito |
| `BusinessReplyAgent`, `BusinessContextAgent` | `@Qualifier("chatLlmClient")` | Fase 3 |
| `DevCodeReviewAgent`, `DevSummaryAgent`, `DevAnswerAgent` | `@Qualifier("codingLlmClient")` | Fase 4 |
| `CryptoMarketAnalystAgent`, `CryptoRiskReviewAgent`, `CryptoFinalSummaryAgent`, `EvaluatorAgent` | `@Qualifier("smartLlmClient")` | Fase 5 |

### 5.3 Contrato de bean naming

```java
@Bean @Qualifier("fastLlmClient")   LlmClient fastLlmClient(...)
@Bean @Qualifier("chatLlmClient")   LlmClient chatLlmClient(...)
@Bean @Qualifier("smartLlmClient")  LlmClient smartLlmClient(...)
@Bean @Qualifier("codingLlmClient") LlmClient codingLlmClient(...)
// Sin @Primary — evitar ambigüedad; todos deben resolverse explícitamente
```

---

## 6. Fases de Implementación

### Fase 0 — Validación manual de modelos (pre-código)

**Objetivo:** Verificar que gemma3 responde correctamente a los prompts de sus agentes destino antes de wiring automático. DeepSeek postergado (requiere GPU ≥24 GB).

**Estado de validación VRAM (completado 2026-06-17):**
- [x] GPU RTX 5080: 16,303 MiB total, 8,294 MiB usados por Qwen vLLM, ~6,800 MiB libre
- [x] `gemma-3-12b-it` BF16 (~24 GB) → descartado en esta GPU
- [x] `DeepSeek-R1-Distill-Qwen-14B` BF16 (~28 GB) → descartado en esta GPU
- [x] `gemma3:12b` Ollama Q4_K_M: 8.15 GB en disco, ~8.5 GB VRAM → validar secuencialmente (Qwen parado)
- [x] `gemma3:4b` Ollama Q4_K_M: 3.34 GB en disco, ~3.5 GB VRAM → cabe junto a Qwen
- [x] Ollama `/v1/chat/completions` OpenAI-compatible verificado en `:11434` (v0.19.0)

**Pasos pendientes — gemma3:12b (requiere parar Qwen temporalmente):**

```bash
# 1. Verificar VRAM libre antes del pull
nvidia-smi --query-gpu=memory.used,memory.free --format=csv,noheader

# 2. Pull del modelo (8.15 GB, solo primera vez)
ollama pull gemma3:12b

# 3. Parar vLLM para liberar 8.3 GB
docker stop local-ai-runtime-vllm

# 4. Smoke test básico
curl -s http://localhost:11434/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma3:12b","messages":[{"role":"user","content":"reply OK only"}],"max_tokens":5}'

# 5. Medir VRAM durante inferencia
nvidia-smi --query-gpu=memory.used --format=csv,noheader

# 6. Test con prompt real de BusinessReplyAgent (copiar SYSTEM_PROMPT de la clase)
time curl -s http://localhost:11434/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma3:12b",
    "messages": [
      {"role":"system","content":"<SYSTEM_PROMPT de BusinessReplyAgent>"},
      {"role":"user","content":"<mensaje de cliente de ejemplo>"}
    ],
    "max_tokens": 512,
    "temperature": 0.3
  }'

# 7. Al terminar: reiniciar vLLM
docker start local-ai-runtime-vllm
```

---

### Resultado validación gemma3:4b — 2026-06-17 ✅

**gemma3:4b pull:** completado, 3.3 GB en disco (`ollama list` confirmado).

**VRAM coexistencia (medición real):**

| Proceso | VRAM usada |
|---------|-----------|
| vLLM (Qwen2.5-Coder-3B) | 8,294 MiB |
| Ollama (gemma3:4b cargado) | 4,024 MiB |
| Chrome GPU + misc | ~252 MiB |
| **Total usado** | **13,492 MiB** |
| **Libre** | **2,340 MiB** |

Qwen vLLM + gemma3:4b coexisten en 16 GB con ~2.3 GB de margen. ✅ **Confirmado simultáneo.**

**Latencias:**

| Request | Tiempo total |
|---------|-------------|
| Cold load (primer request tras pull) | **16.1 s** |
| Warm (modelo ya en VRAM) | **0.16 s** |
| Prompt BusinessReplyAgent (~270 tokens) | **0.95 s** |

**Test BusinessReplyAgent** (prompt real simplificado, temperatura 0.3, max_tokens=512):

- Tokens: 270 prompt + 135 completion
- Respuesta en español natural: ✅ (usó nombre de cliente, horarios del knowledgeBase)
- Schema JSON respetado: ✅ (todos los campos: response, intent, confidence, handoffRequired, leadCaptured, channel, sources)
- **Problema detectado: markdown fence wrapping** — el modelo devuelve la respuesta envuelta en ` ```json ... ``` ` en lugar de JSON limpio. `JSON.parse()` falla directamente.

**Respuesta raw recibida:**
```
```json
{
  "response": "Hola María, nuestros horarios de atención son de lunes a viernes de 9:00 a 18:00...",
  "intent": "INQUIRY",
  ...
}
```
```

**Análisis del problema de fences:**
- El prompt dice "Reply with STRICT JSON ONLY. No markdown fences." pero gemma3:4b lo ignora.
- Qwen2.5-Coder-3B (modelo actual) no tiene este problema con los mismos prompts.
- **Solución requerida en Fase 3:** stripping de fences en `OpenAiCompatibleLlmClient.toLlmResponse()` condicionado por endpoint/modelo, o prompt más agresivo con ejemplos few-shot.
- Esto es un **bloqueante para Fase 3** — debe resolverse antes de conectar gemma al `BusinessReplyAgent`.

**Criterios de completitud — gemma3:4b:**
- [x] Pull completado y confirmado en `ollama list`
- [x] Smoke test devuelve respuesta coherente
- [x] VRAM medida: 13,492 MiB total con ambos modelos activos
- [x] Coexistencia con Qwen vLLM confirmada sin parar nada
- [x] Latencia warm: 0.16 s (excelente)
- [x] Prompt BusinessReplyAgent → respuesta natural en español con schema correcto
- [x] Problema documentado: markdown fence wrapping requiere stripper antes de Fase 3
- [ ] gemma3:12b pendiente (requiere parar Qwen, fase posterior)

**Ollama endpoint:** `http://localhost:11434/v1/chat/completions` — OpenAI-compatible, habla con `OpenAiCompatibleLlmClient` sin modificaciones de código. ✅

**Herramienta:** curl directo a `http://localhost:11434/v1/chat/completions`

---

### Fase 1 — Config multi-modelo sin cambiar agentes

**Objetivo:** Infraestructura de Spring beans lista, todos los agentes siguen apuntando al default.

**Cambios:**
1. Crear `RuntimeMultiModelProperties` con los 4 subrecords
2. Crear `LlmMultiClientConfiguration` con 4 beans nombrados pero sin `@Primary`
3. Actualizar `LlmWebClientConfiguration` para no romper el bean existente `llmWebClient`
4. Agregar config YAML de los 4 roles con defaults seguros (todos apuntando a `localhost:8000`)
5. Crear `docker-compose.vllm-multi.yml` en `infra/`

**Invariante:** Todos los agentes siguen usando el bean `OpenAiCompatibleLlmClient` único. Ningún agente cambia. Ningún test rompe.

**Criterios de completitud:**
- [ ] `./gradlew test` verde
- [ ] App arranca con y sin las variables de entorno de los nuevos roles
- [ ] Health endpoint devuelve 200 apuntando a localhost:8000

---

### Fase 2 — Métricas por modelRole

**Objetivo:** Grafana muestra latencia/errores separados por rol antes de rutear agentes.

**Cambios:**
1. Agregar `LlmModelRole` enum
2. Modificar `RuntimeMetrics.recordLlmCall(String model, String status)` → agregar overload con `LlmModelRole`
3. Modificar `LlmAgentSupport.callLlm` para recibir `LlmModelRole` (con default para backward compat)
4. Los 4 beans de `LlmMultiClientConfiguration` exponen su role en el tag

**Criterios de completitud:**
- [ ] Métrica `runtime.llm.calls{model_role="fast"}` aparece en Prometheus
- [ ] No hay regresión en métricas existentes (`model` tag sigue presente)

---

### Fase 3 — BusinessReplyAgent + BusinessContextAgent usan CHAT_MODEL

**Objetivo:** Respuestas customer-facing usan gemma-3-12b-it.

**Cambios:**
1. `BusinessReplyAgent(LlmClient llmClient, ...)` → `BusinessReplyAgent(@Qualifier("chatLlmClient") LlmClient llmClient, ...)`
2. Idem `BusinessContextAgent`
3. Ajustar `application-local.yml` o script de infra para levantar instancia chat en 8001

**Criterios de completitud:**
- [ ] `LLM_CALL_SUCCEEDED` event de `business-reply-agent` muestra `model=google/gemma-3-12b-it`
- [ ] Respuesta en lenguaje natural coherente (smoke test manual)
- [ ] `business-context-agent` ídem

---

### Fase 4 — Dev agents usan CODING_MODEL

**Objetivo:** Respuestas técnicas y code review usan el modelo coder.

**Cambios:**
1. `@Qualifier("codingLlmClient")` en `DevCodeReviewAgent`, `DevSummaryAgent`, `DevAnswerAgent`
2. `max-tokens` de CODING_MODEL validado para respuestas de code review (mínimo 1024)

**Criterios de completitud:**
- [ ] `dev-code-review-agent` completa un run sin truncamiento
- [ ] `LlmCallRecord.rawResponse` no termina en mid-sentence para respuestas técnicas típicas

---

### Fase 5 — Crypto / Evaluator usan SMART_MODEL

**Objetivo:** Análisis financiero y evaluación policy usan DeepSeek-R1-Distill.

**Cambios:**
1. `@Qualifier("smartLlmClient")` en `CryptoMarketAnalystAgent`, `CryptoRiskReviewAgent`, `CryptoFinalSummaryAgent`, `EvaluatorAgent`
2. Implementar stripping de bloque `<think>...</think>` en `OpenAiCompatibleLlmClient.toLlmResponse()` condicional por modelo o por flag config
3. Ajustar `LlmCallRecord.rawResponse` truncamiento a 16000 chars para thinking models (o archivar el `<think>` block separado en `metadata`)

**Criterios de completitud:**
- [ ] `crypto-market-analyst-agent` produce JSON válido (sin `<think>` en `parsedResponse`)
- [ ] `LlmCallRecord.rawResponse` preserva el `<think>` block completo (diagnóstico)
- [ ] Run de crypto market review completa en <3 minutos end-to-end

---

### Fase 6 — Fallback + ATP quality scoring

**Objetivo:** Degradación graceful cuando un modelo no está disponible + scoring por rol.

**Cambios:**
1. `LlmMultiClientConfiguration` puede configurar fallback role (`smart.fallback: fast`)
2. ATP quality metrics separadas por `modelRole` para detectar regresiones al caer en fallback
3. Alert: si el fallback se activa en producción, disparar alerta (no silencioso)

**Criterios de completitud:**
- [ ] Con `vllm-smart` detenido, `CryptoMarketAnalystAgent` cae a FAST con warning en eventos
- [ ] El warning aparece en el `RunDetail` del cockpit
- [ ] ATP baseline diferencia calidad SMART vs FAST en las tareas de crypto

---

## 7. Tests Futuros (no implementar en esta spec)

### Unit tests
- `MultiModelBeanWiringTest`: verifica que los 4 qualifiers resuelven beans distintos en el contexto Spring
- `LlmModelRoleMetricsTest`: verifica que `recordLlmCall` emite el tag `model_role` correcto
- `DeepSeekThinkStripperTest`: verifica extracción del contenido post-`</think>` con varios formatos de output

### Integration tests
- `FastModelSmokeTest`: ClassifierAgent produce JSON válido en <5s contra localhost:8000
- `ChatModelSmokeTest`: BusinessReplyAgent produce respuesta sin JSON contra localhost:8001
- `SmartModelSmokeTest`: CryptoMarketAnalystAgent produce JSON válido tras think-strip contra localhost:8002
- `CodingModelSmokeTest`: DevCodeReviewAgent completa sin truncamiento contra localhost:8000

### Contract tests
- FAST: output siempre parseable como JSON con el schema del agente
- CHAT: output nunca contiene `{` como primer carácter (no es JSON crudo)
- SMART: output post-strip es JSON válido con todos los campos requeridos

### Performance
- P95 latency per role bajo carga sostenida (5 req/min)
- VRAM headroom: ningún modelo excede 90% GPU memory durante pico

### ATP integration
- Baseline separado por `modelRole` para detectar regresiones de calidad al cambiar de modelo

---

## 8. Riesgos

### R1 — VRAM insuficiente para múltiples instancias simultáneas

**Hallazgo Fase 0 (medición real, 2026-06-17):**

| Componente | VRAM usada |
|-----------|-----------|
| GPU total (RTX 5080) | 16,303 MiB |
| vLLM container Qwen2.5-Coder-3B | **8,294 MiB** |
| Display + misc | ~1,076 MiB |
| **Libre** | **~6,800 MiB** |

**Conclusión:** Los modelos BF16 originales (`gemma-3-12b` ~24 GB, `DeepSeek-R1-14B` ~28 GB) son **inviables** en esta GPU. Descartados definitivamente.

**Opciones viables para CHAT_MODEL en RTX 5080:**

| Opción | GGUF size | VRAM estimada | Simultáneo con Qwen? |
|--------|-----------|--------------|----------------------|
| `gemma3:12b` Ollama Q4_K_M | 8.15 GB | ~8.5–9 GB | **NO** (total ~17 GB) |
| `gemma3:4b` Ollama Q4_K_M | 3.34 GB | ~3.5–4 GB | **SÍ** (total ~12 GB) |

**Decisión adoptada:** `gemma3:12b` requiere parar Qwen para validación (Fase 0). Para operación simultánea en esta GPU, la única opción es `gemma3:4b`.

**SMART_MODEL:** DeepSeek-R1-14B queda pospuesto hasta contar con GPU ≥24 GB o instancia multi-GPU.

**Mitigación:**
- Fase 0 valida gemma3:12b secuencialmente (Qwen parado)
- Decisión de producción se toma después de la validación
- Si simultáneo es requerido: usar `gemma3:4b` en Ollama

**Umbral de alerta:** Si VRAM > 90% durante inferencia con ambos modelos activos, reducir `keep_alive` en Ollama

---

### R2 — Latencia de SMART_MODEL inaceptable para crypto workflows

**Descripción:** DeepSeek-R1-Distill genera tokens de razonamiento antes de producir output útil. Un análisis de mercado puede tardar 60-120s extra.

**Mitigación:**
- Timeout configurado en 180s para SMART_MODEL
- ATP mide latencia P95 por rol separado
- Fase 5 incluye smoke test de latencia antes de activar en producción

---

### R3 — Fallback a FAST degrada calidad sin aviso visible

**Descripción:** Si SMART_MODEL no responde y se cae a FAST, los resultados de crypto market review pueden ser incorrectos silenciosamente.

**Mitigación:**
- Fallback siempre emite `LLM_CALL_FAILED` + warning en `RunDetail`
- ATP baseline diferencia scores por `modelRole` para detectar la degradación
- No implementar fallback silencioso — siempre visible en cockpit

---

### R4 — DeepSeek-R1 genera bloque `<think>` en rawResponse

**Descripción:** El modelo R1 produce `<think>Razonamiento interno...</think>\nRespuesta útil`. Si no se stripea, `LlmCallRecord.rawResponse` y `parsedResponse` contienen el bloque de pensamiento, rompiendo el JSON parser downstream.

**Mitigación:**
- Implementar `ThinkBlockStripper` antes de pasar el contenido a agentes
- Guardar el bloque `<think>` completo en `LlmCallRecord.metadata` para diagnóstico
- El stripper debe ser condicional por modelo (no aplicar a gemma ni qwen)
- Cubrir con `DeepSeekThinkStripperTest` antes de activar Fase 5

---

### R5 — Truncamiento de rawResponse en LlmCallRecord

**Descripción:** `LlmAgentSupport` trunca `rawResponse` a 8000 chars. Con thinking models, el bloque `<think>` puede ocupar >8000 chars, truncando el output útil.

**Mitigación:**
- Aplicar el think-strip **antes** del truncamiento
- Opcionalmente separar `rawResponse` en `thinkContent` (archivado) + `outputContent` (lo útil)
- El límite de 8000 chars debe aplicarse al contenido post-strip

---

### R6 — maxTokens 192 insuficiente para modelos nuevos

**Descripción:** El default actual de 192 tokens es suficiente para Qwen-3B en tareas JSON pequeñas. Gemma-12B para respuestas chat necesita 512+. DeepSeek-R1-14B para razonamiento necesita 2048+.

**Mitigación:**
- Cada role config tiene su propio `max-tokens` (ver Sección 4.2)
- Fase 0 valida que `max-tokens` no causa truncamiento mid-response en los prompts reales

---

### R7 — Markdown fence wrapping en gemma3:4b (confirmado Fase 0) ⚠️

**Descripción (hallazgo 2026-06-17):** `gemma3:4b` envuelve el JSON en bloques markdown (` ```json ... ``` `) incluso cuando el prompt dice explícitamente "STRICT JSON ONLY. No markdown fences." El parser downstream recibe una cadena que no es JSON válido y falla al parsear.

`Qwen2.5-Coder-3B-Instruct` (modelo actual) no tiene este comportamiento.

**Ejemplo del problema:**
```
# Lo que devuelve gemma3:4b:
```json
{"response": "...", "intent": "INQUIRY", ...}
```

# Lo que espera el parser:
{"response": "...", "intent": "INQUIRY", ...}
```

**Impacto:** Bloqueante para Fase 3. Si se conecta gemma3:4b a `BusinessReplyAgent` sin fix, todos los runs de business-chat fallarán en el paso de parseo JSON.

**Mitigación antes de Fase 3:**
- Implementar fence stripper en `OpenAiCompatibleLlmClient.toLlmResponse()`: detectar y remover ` ```json ... ``` ` y ` ``` ... ``` ` del contenido antes de devolverlo
- El stripper debe ser condicional por modelo (no aplicar a Qwen que ya devuelve JSON limpio), O
- Aplicar de forma global como normalización defensiva (más simple, sin riesgo de regresión)
- Alternativa de prompt: few-shot con ejemplo de output limpio, aunque menos confiable
- Cubrir con `MarkdownFenceStripperTest` antes de activar Fase 3

**Severidad:** Alta — bloqueante para activar CHAT_MODEL

---

## 9. Criterios de Aceptación

### Por Fase

**Fase 0:**
- Cada modelo validado manualmente responde correctamente al prompt de su agente destino
- VRAM real documentada (no estimada) para cada modelo

**Fase 1:**
- `./gradlew test` verde sin cambios en comportamiento
- App arranca con config legacy (sin las variables de entorno nuevas)
- Los 4 beans nuevos registrados en contexto Spring sin conflicto con el bean original

**Fase 2:**
- `runtime.llm.calls{model_role}` visible en Prometheus para cada rol activo
- Sin regresión en dashboards Grafana existentes

**Fase 3:**
- Fence stripper implementado y cubierto por `MarkdownFenceStripperTest` (prereq antes de conectar gemma)
- `business-reply-agent` usa `gemma3:4b` o `gemma3:12b` (verificado en `LlmCallRecord`)
- Respuestas pasan review manual de calidad (lenguaje natural, sin fuga de system prompt)
- `JSON.parse()` del campo `response` no falla en ningún run de smoke test

**Fase 4:**
- `dev-code-review-agent` completa sin truncamiento en análisis de código real
- `LlmCallRecord.rawResponse` contiene respuesta completa (no cortada)

**Fase 5:**
- `crypto-market-analyst-agent` produce JSON válido sin contenido `<think>`
- Run completo de crypto market review en <3 minutos
- `<think>` block archivado en metadata (disponible para diagnóstico)

**Fase 6:**
- Fallback visible en cockpit cuando modelo principal no responde
- ATP score diferenciado por `modelRole`

---

## 10. Archivos Relevantes

```
runtime/life-engine-runtime/
  src/main/java/io/lifeengine/runtime/llm/
    LlmClient.java                    — interfaz (no cambiar estructura)
    OpenAiCompatibleLlmClient.java    — implementación (agregar think-strip en Fase 5)
    RuntimeLlmProperties.java         — properties existentes (no romper)
    LlmWebClientConfiguration.java    — agregar nuevos beans en Fase 1
    LlmRetryConfig.java               — sin cambio
    LlmCallRecord.java                — agregar metadata keys en Fase 5
    LlmAgentSupport.java              — agregar model_role en Fase 2
  src/main/java/io/lifeengine/runtime/agents/
    ClassifierAgent.java              — @Qualifier en Fase 3+
    SummarizerAgent.java              — @Qualifier en Fase 3+
    EvaluatorAgent.java               — @Qualifier en Fase 5
    ExtractorAgent.java               — @Qualifier en Fase 3+
  src/main/java/io/lifeengine/runtime/ext/
    businesschat/stages/BusinessReplyAgent.java   — Fase 3
    businesschat/stages/BusinessContextAgent.java — Fase 3
    businesschat/stages/LeadCaptureAgent.java     — Fase 3
    devcodereview/stages/DevCodeReviewAgent.java  — Fase 4
    devcodereview/stages/DevSummaryAgent.java     — Fase 4
    devknowledgeanswer/stages/DevAnswerAgent.java — Fase 4
    cryptomarketreview/stages/CryptoMarketAnalystAgent.java  — Fase 5
    cryptomarketreview/stages/CryptoRiskReviewAgent.java     — Fase 5
    cryptomarketreview/stages/CryptoFinalSummaryAgent.java   — Fase 5
  src/main/resources/application.yml  — agregar roles fast/chat/smart/coding
  src/main/java/io/lifeengine/runtime/observability/RuntimeMetrics.java — Fase 2

infra/
  docker-compose.vllm.yml             — no modificar (instancia single existente)
  docker-compose.vllm-multi.yml       — nuevo, Fase 1
```
