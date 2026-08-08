package io.lifeengine.runtime.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Endpoints LLM opcionales por rol (ADR-ML-001 fase 1).
 *
 * <p>{@code chat} es el rol conversacional — respuestas que lee una persona. {@code fast} es el rol
 * de clasificación/extracción, donde importa la latencia y no la prosa. Un rol sin configurar
 * hereda entero el default de {@code runtime.llm}.
 */
@ConfigurationProperties(prefix = "runtime.llm.roles")
public record RuntimeLlmRolesProperties(
        RuntimeLlmRoleProperties chat, RuntimeLlmRoleProperties fast) {

    public RuntimeLlmRoleProperties chatOrEmpty() {
        return chat == null ? RuntimeLlmRoleProperties.empty() : chat;
    }

    public RuntimeLlmRoleProperties fastOrEmpty() {
        return fast == null ? RuntimeLlmRoleProperties.empty() : fast;
    }
}
