package io.lifeengine.runtime.api;

import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Flat run state returned by POST /runs and embedded in run detail. */
public record RunResponse(
        UUID runId,
        String workflowId,
        String correlationId,
        RunStatus status,
        Instant startedAt,
        Instant updatedAt,
        String message,
        List<String> warnings,
        Map<String, Object> metadata) {

    public RunResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static RunResponse from(Run run) {
        return from(run, statusMessage(run.status()), List.of());
    }

    public static RunResponse from(Run run, String message, List<String> warnings) {
        return new RunResponse(
                run.id(),
                run.workflowId(),
                run.correlationId(),
                run.status(),
                run.startedAt() != null ? run.startedAt() : run.createdAt(),
                run.updatedAt(),
                message,
                warnings,
                run.metadata());
    }

    private static String statusMessage(RunStatus status) {
        return switch (status) {
            case QUEUED -> "Run queued";
            case RUNNING -> "Run executing";
            case SUCCEEDED -> "Run succeeded";
            case FAILED -> "Run failed";
            case CANCELLED -> "Run cancelled";
        };
    }
}
