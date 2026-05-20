package io.lifeengine.runtime.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Append-only runtime event for a single run. */
public record RuntimeEvent(
        UUID eventId,
        UUID runId,
        String type,
        Instant occurredAt,
        String source,
        Map<String, String> attributes,
        boolean terminal) {

    public RuntimeEvent {
        type = type == null ? "" : type.strip();
        source = source == null ? "runtime-core" : source.strip();
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static RuntimeEvent of(UUID runId, String type, Map<String, String> attributes, boolean terminal) {
        return new RuntimeEvent(
                UUID.randomUUID(),
                runId,
                type,
                Instant.now(),
                "runtime-core",
                attributes,
                terminal);
    }
}
