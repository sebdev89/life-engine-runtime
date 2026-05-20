package io.lifeengine.runtime.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Persisted run aggregate (in-memory store in this scaffold). */
public record Run(
        UUID id,
        RunStatus status,
        String workflowId,
        String correlationId,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> metadata) {

    public Run {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public Run withStatus(RunStatus newStatus, Instant now) {
        return new Run(
                id,
                newStatus,
                workflowId,
                correlationId,
                createdAt,
                now,
                startedAt != null ? startedAt : (newStatus == RunStatus.RUNNING ? now : null),
                newStatus.isTerminal() ? now : finishedAt,
                metadata);
    }

    public Run withStartedAt(Instant started) {
        return new Run(
                id,
                status,
                workflowId,
                correlationId,
                createdAt,
                updatedAt,
                started,
                finishedAt,
                metadata);
    }
}
