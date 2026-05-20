package io.lifeengine.runtime.llm;

import java.time.Instant;

/** In-flight or completed LLM call observability record. */
public record LlmCallRecord(
        String agentId,
        String model,
        String status,
        long latencyMs,
        String promptPreview,
        String responsePreview,
        String error,
        Instant startedAt) {}
