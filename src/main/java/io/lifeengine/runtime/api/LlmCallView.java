package io.lifeengine.runtime.api;

import io.lifeengine.runtime.llm.LlmCallRecord;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** API contract for one LLM invocation on a run. */
public record LlmCallView(
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

    public static LlmCallView from(LlmCallRecord record) {
        return new LlmCallView(
                record.id(),
                record.stageId(),
                record.agentId(),
                record.provider(),
                record.model(),
                record.prompt(),
                record.rawResponse(),
                record.parsedResponse(),
                record.parseError(),
                record.startedAt(),
                record.finishedAt(),
                record.durationMs(),
                record.metadata());
    }
}
