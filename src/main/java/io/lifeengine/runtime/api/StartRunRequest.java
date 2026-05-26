package io.lifeengine.runtime.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Body for {@code POST /api/runtime/runs} (RunRequest contract). */
public record StartRunRequest(
        @NotBlank @Size(max = 128) String workflowId,
        @NotBlank @Size(max = 32_000) String input,
        @Size(max = 128) String correlationId,
        Map<String, Object> metadata) {

    public StartRunRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
