package io.lifeengine.runtime.llm;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Persisted LLM invocation for run detail / cockpit replay. */
public record LlmCallRecord(
        UUID id,
        String stageId,
        String agentId,
        String provider,
        String model,
        String prompt,
        String rawResponse,
        String parsedResponse,
        String parseError,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        Map<String, String> metadata) {

    public LlmCallRecord {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
