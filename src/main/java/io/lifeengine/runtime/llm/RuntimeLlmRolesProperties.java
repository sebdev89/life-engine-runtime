package io.lifeengine.runtime.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional per-role LLM endpoints (ADR-ML-001 phase 1). */
@ConfigurationProperties(prefix = "runtime.llm.roles")
public record RuntimeLlmRolesProperties(RuntimeLlmRoleProperties chat, RuntimeLlmRoleProperties fast) {

    public RuntimeLlmRoleProperties chatOrEmpty() {
        return chat == null ? RuntimeLlmRoleProperties.empty() : chat;
    }

    public RuntimeLlmRoleProperties fastOrEmpty() {
        return fast == null ? RuntimeLlmRoleProperties.empty() : fast;
    }
}
