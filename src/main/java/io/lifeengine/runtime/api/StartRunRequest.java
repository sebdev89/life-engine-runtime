package io.lifeengine.runtime.api;

import jakarta.validation.constraints.Size;
import java.util.Map;

/** Body for {@code POST /api/runtime/runs}. */
public record StartRunRequest(
        @Size(max = 128) String workflowId,
        @Size(max = 128) String correlationId,
        @Size(max = 32_000) String input,
        Map<String, Object> metadata) {

    public StartRunRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
