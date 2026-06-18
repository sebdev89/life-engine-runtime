# ADR: Multi-Modelo LLM en Runtime

**ID:** ADR-runtime-multi-model-llm  
**Estado:** Propuesto  
**Fecha:** 2026-06-17  
**Spec relacionada:** [runtime-multi-model-llm](../specs/runtime-multi-model-llm.md)

---

## Contexto

Runtime tiene una sola capa LLM: `OpenAiCompatibleLlmClient` es el único bean de tipo `LlmClient`, configurado por `RuntimeLlmProperties` (prefix `runtime.llm`). Los 11 agentes de producción inyectan `LlmClient` sin `@Qualifier` y reciben este único bean, que apunta a `Qwen/Qwen2.5-Coder-3B-Instruct` en vLLM (`localhost:8000`).

El inventario de modelos disponibles localmente ha crecido:

- `Qwen/Qwen2.5-Coder-3B-Instruct` — en producción, estructuras JSON pequeñas, bajo latencia
- `google/gemma-3-12b-it` — instruction-tuned, apto para respuestas customer-facing en lenguaje natural
- `deepseek-ai/DeepSeek-R1-Distill-Qwen-14B` — modelo de razonamiento, genera `<think>` chains antes del output

El problema central: usar Qwen-3B para generar respuestas conversacionales (BusinessReplyAgent) no produce la calidad de lenguaje natural que requiere un agente customer-facing. Igualmente, usar el mismo modelo para razonamiento financiero complejo (CryptoMarketAnalystAgent) desperdicia la ventaja de los modelos disponibles.

---

## Decisión

Introducir 4 roles de modelo con beans Spring independientes, identificados con `@Qualifier`, usando la misma interfaz `LlmClient` y la misma implementación `OpenAiCompatibleLlmClient`.

**Roles:**
- `FAST_MODEL` — Qwen2.5-Coder-3B: clasificación, extracción, routing, JSON pequeño
- `CHAT_MODEL` — Gemma-3-12B-IT: respuestas customer-facing, lenguaje natural
- `SMART_MODEL` — DeepSeek-R1-Distill-Qwen-14B: razonamiento complejo, evaluación, crypto
- `CODING_MODEL` — Qwen2.5-Coder-3B (mismo modelo, config distinta): dev agents, code review

Los agentes migran a su rol designado en fases incrementales. Hasta que cada agente sea migrado explícitamente, sigue usando el bean default sin `@Qualifier` (backward compatible).

---

## Opciones Evaluadas

### Opción A: Router dinámico por contenido (descartada)

Un componente intermedio analiza el prompt y selecciona el modelo en tiempo de ejecución.

**Descartada porque:**
- Introduce latencia extra en el happy path
- Lógica de routing no determinista, difícil de testear
- Complejidad de observabilidad (¿qué modelo eligió el router y por qué?)
- No necesario: los agentes ya tienen roles semánticos claros

### Opción B: LangChain / framework de orquestación (descartada)

Reemplazar el stack LLM por LangChain4j u otro framework con multi-model soporte nativo.

**Descartada porque:**
- Cambio de comportamiento global, no incremental
- Introduce dependencia pesada con su propio modelo de abstracciones
- La interfaz `LlmClient` actual es simple y funciona; no hay razón para reemplazarla
- Riesgo alto de regresiones en todos los agentes simultáneamente

### Opción C: @Qualifier + beans dedicados por rol (elegida)

Reutilizar exactamente la misma interfaz `LlmClient` y la misma implementación `OpenAiCompatibleLlmClient`, instanciada 4 veces con distintas `RuntimeLlmProperties` (una por role).

**Elegida porque:**
- Zero cambio de comportamiento hasta que cada agente es migrado explícitamente
- Rollout incremental agente por agente
- Testeable: cada bean resuelve un `@Qualifier` distinto y puede mockearse independientemente
- La implementación `OpenAiCompatibleLlmClient` ya soporta cualquier endpoint OpenAI-compatible
- Compatible con vLLM multi-instancia (un proceso por modelo, cada uno en su puerto)

---

## Consecuencias

### Positivas

- Cada clase de tarea usa el modelo óptimo para su naturaleza
- La latencia de los agentes FAST no se ve afectada por la carga de los agentes SMART
- Las métricas por `modelRole` permiten detectar regresiones de calidad modelo a modelo
- El rollout incremental limita el blast radius: un agente fallando no afecta a los demás
- Backward compatibility total: agentes no migrados siguen funcionando igual

### Negativas / Trade-offs

- Requiere múltiples instancias vLLM corriendo simultáneamente en producción
- VRAM total en uso es significativamente mayor
- El bean naming con `@Qualifier` requiere disciplina: un agente migrado que pierde el qualifier silenciosamente cae de vuelta al bean default (sin error en compile time)
- `DeepSeek-R1-Distill` requiere think-stripping antes de parsear el output, agregando complejidad a `OpenAiCompatibleLlmClient`
- El tiempo de warmup de los modelos grandes (gemma, deepseek) puede ser de 30-120s al iniciar

### Riesgos clave

| Riesgo | Severidad | Mitigación |
|--------|-----------|------------|
| VRAM insuficiente para instancias simultáneas | Alta | Medir en Fase 0; empezar sin simultaneidad |
| DeepSeek `<think>` rompe JSON parsers | Alta | Think-stripper antes de Fase 5 |
| Fallback silencioso degrada calidad crypto | Alta | Fallback siempre visible en cockpit |
| `maxTokens=192` trunca modelos grandes | Media | Config por role con valores apropiados |
| Timeout 30s insuficiente para DeepSeek | Media | SMART_MODEL timeout=180s |

---

## Invariantes que esta decisión preserva

1. **Interfaz `LlmClient` no cambia.** Todos los agentes siguen usando la misma interfaz.
2. **`OpenAiCompatibleLlmClient` sigue siendo la única implementación.** No se crea una implementación nueva por modelo.
3. **No hay router dinámico.** La elección de modelo es estática en tiempo de compilación (vía `@Qualifier`).
4. **Sin LangChain.** El stack de orquestación no cambia.
5. **`runtime.llm.*` sigue funcionando.** El bean default no se rompe ni se mueve.
6. **Cada fase es reversible.** Revertir un `@Qualifier` a la inyección sin qualifier restaura el comportamiento anterior.

---

## Implementación

Ver [Spec detallada](../specs/runtime-multi-model-llm.md) para:
- Asignación de agentes por rol
- Config YAML completa
- Fases de implementación (0-6)
- Riesgos detallados con mitigaciones
- Criterios de aceptación verificables
- Tests futuros

---

## Revisores requeridos antes de implementar Fase 1

- Validar Fase 0 completada (VRAM real medida, prompts validados manualmente)
- Confirmar puertos 8001 y 8002 disponibles en el entorno local
- Revisar `LlmWebClientConfiguration` para evitar conflicto con el bean `llmWebClient` existente
