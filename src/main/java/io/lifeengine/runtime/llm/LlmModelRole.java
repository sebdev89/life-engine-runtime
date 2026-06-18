package io.lifeengine.runtime.llm;

/**
 * Semantic role used to select the appropriate {@link LlmClient} bean via {@code @Qualifier}.
 *
 * <p>Roles are assigned per-agent in phases (see docs/specs/runtime-multi-model-llm.md).
 * Fase 1 wires the beans; agents migrate to their target role in Fase 3–5.
 */
public enum LlmModelRole {
    /** Fast JSON extraction, classification, routing. Low-latency required. */
    FAST,
    /** Customer-facing natural-language replies. Instruction-tuned, conversational. */
    CHAT,
    /** Complex reasoning, financial analysis, policy evaluation. */
    SMART,
    /** Code review, dev knowledge answers, technical structured output. */
    CODING
}
