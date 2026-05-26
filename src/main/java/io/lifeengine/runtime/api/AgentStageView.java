package io.lifeengine.runtime.api;

import io.lifeengine.runtime.domain.AgentStageRecord;
import java.time.Instant;
import java.util.Map;

/** API contract for one executed workflow stage. */
public record AgentStageView(
        String stageId,
        String stageType,
        String name,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String input,
        String output,
        String error,
        Map<String, String> metadata) {

    public static AgentStageView from(AgentStageRecord record) {
        return new AgentStageView(
                record.stageId(),
                record.stageType(),
                record.name(),
                record.status(),
                record.startedAt(),
                record.finishedAt(),
                record.durationMs(),
                record.input(),
                record.output(),
                record.error(),
                record.metadata());
    }
}
