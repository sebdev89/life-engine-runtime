package io.lifeengine.runtime.api;

import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** JSON view of a {@link Run}. */
public record RunResponse(
        UUID runId,
        RunStatus status,
        String workflowId,
        String correlationId,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> metadata) {

    public static RunResponse from(Run run) {
        return new RunResponse(
                run.id(),
                run.status(),
                run.workflowId(),
                run.correlationId(),
                run.createdAt(),
                run.updatedAt(),
                run.startedAt(),
                run.finishedAt(),
                run.metadata());
    }
}
