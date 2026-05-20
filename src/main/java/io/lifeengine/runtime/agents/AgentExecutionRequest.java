package io.lifeengine.runtime.agents;

import java.util.Map;
import java.util.UUID;

public record AgentExecutionRequest(
        UUID runId,
        String agentId,
        String input,
        Map<String, String> context) {

    public AgentExecutionRequest {
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
