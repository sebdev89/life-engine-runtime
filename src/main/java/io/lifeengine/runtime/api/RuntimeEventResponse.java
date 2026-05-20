package io.lifeengine.runtime.api;

import io.lifeengine.runtime.domain.RuntimeEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** SSE / JSON payload for a single runtime event. */
public record RuntimeEventResponse(
        UUID eventId,
        UUID runId,
        String type,
        Instant occurredAt,
        String source,
        Map<String, String> attributes,
        boolean terminal) {

    public static RuntimeEventResponse from(RuntimeEvent event) {
        return new RuntimeEventResponse(
                event.eventId(),
                event.runId(),
                event.type(),
                event.occurredAt(),
                event.source(),
                event.attributes(),
                event.terminal());
    }
}
