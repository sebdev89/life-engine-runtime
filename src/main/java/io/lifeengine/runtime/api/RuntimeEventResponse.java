package io.lifeengine.runtime.api;

import io.lifeengine.runtime.domain.RuntimeEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Canonical runtime event for SSE and run detail replay. */
public record RuntimeEventResponse(
        UUID eventId,
        UUID runId,
        String workflowId,
        String correlationId,
        String type,
        String stageId,
        String stageType,
        String agentId,
        String toolId,
        Instant timestamp,
        Map<String, String> payload,
        boolean terminal) {

    private static final Set<String> TOP_LEVEL_KEYS =
            Set.of(
                    "workflowId",
                    "correlationId",
                    "stageId",
                    "stageType",
                    "agentId",
                    "toolId",
                    "refId",
                    "order");

    public RuntimeEventResponse {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static RuntimeEventResponse from(RuntimeEvent event) {
        Map<String, String> attrs = event.attributes();
        String workflowId = attrs.getOrDefault("workflowId", "");
        String correlationId = attrs.getOrDefault("correlationId", "");
        String stageId = attrs.get("stageId");
        String stageType = attrs.get("stageType");
        String agentId = attrs.get("agentId");
        String toolId = attrs.get("toolId");
        if (toolId == null && attrs.containsKey("refId") && "TOOL".equals(stageType)) {
            toolId = attrs.get("refId");
        }
        Map<String, String> payload = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            if (!TOP_LEVEL_KEYS.contains(entry.getKey())) {
                payload.put(entry.getKey(), entry.getValue());
            }
        }
        return new RuntimeEventResponse(
                event.eventId(),
                event.runId(),
                workflowId,
                correlationId,
                event.type(),
                stageId,
                stageType,
                agentId,
                toolId,
                event.occurredAt(),
                payload,
                event.terminal());
    }
}
