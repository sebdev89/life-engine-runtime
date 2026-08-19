package io.lifeengine.runtime.llm;

import java.time.Duration;

/** Partial override for a role-specific LLM client. Blank fields inherit from {@link RuntimeLlmProperties}. */
public record RuntimeLlmRoleProperties(
        String baseUrl, String model, String apiKey, Duration timeout, Integer maxTokens, Double temperature) {

    static RuntimeLlmRoleProperties empty() {
        return new RuntimeLlmRoleProperties(null, null, null, null, null, null);
    }

    RuntimeLlmProperties merge(RuntimeLlmProperties defaults) {
        return new RuntimeLlmProperties(
                hasText(baseUrl) ? baseUrl.trim() : defaults.baseUrl(),
                hasText(model) ? model.trim() : defaults.model(),
                apiKey != null ? apiKey : defaults.apiKey(),
                timeout != null ? timeout : defaults.timeout(),
                maxTokens != null && maxTokens > 0 ? maxTokens : defaults.maxTokens(),
                temperature != null ? temperature : defaults.temperature(),
                defaults.retry());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
