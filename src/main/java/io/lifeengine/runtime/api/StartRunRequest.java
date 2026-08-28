package io.lifeengine.runtime.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Body for {@code POST /api/runtime/runs} (RunRequest contract).
 *
 * <p>{@code idempotencyKey} is optional. When present, a repeated start with the same key returns
 * the already-created run (HTTP 200) instead of creating a new one; uniqueness is enforced by a
 * partial unique index on {@code runtime_run.idempotency_key}.
 */
public record StartRunRequest(
        @NotBlank @Size(max = 128) String workflowId,
        @NotBlank @Size(max = 32_000) String input,
        @Size(max = 128) String correlationId,
        Map<String, Object> metadata,
        @Size(max = 128) String idempotencyKey) {

    public StartRunRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience constructor predating {@code idempotencyKey}. */
    public StartRunRequest(
            String workflowId, String input, String correlationId, Map<String, Object> metadata) {
        this(workflowId, input, correlationId, metadata, null);
    }
}
