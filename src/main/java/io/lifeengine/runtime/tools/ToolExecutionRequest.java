package io.lifeengine.runtime.tools;

import java.util.Map;
import java.util.UUID;

public record ToolExecutionRequest(UUID runId, String toolId, String input, Map<String, String> context) {

    public ToolExecutionRequest {
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
