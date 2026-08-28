package io.lifeengine.runtime.llm;

import java.util.List;

/**
 * Chat-completion request. {@code maxTokens} is an optional per-request override; when {@code null}
 * (or non-positive) the adapter falls back to its configured default
 * ({@code runtime.llm.max-tokens}). Extension modules whose outputs do not fit the global default
 * (sized for short structured JSON) pass an explicit value here.
 */
public record LlmRequest(String model, List<LlmMessage> messages, Integer maxTokens) {

    /** Convenience constructor for call sites that use the adapter's default max-tokens. */
    public LlmRequest(String model, List<LlmMessage> messages) {
        this(model, messages, null);
    }
}
