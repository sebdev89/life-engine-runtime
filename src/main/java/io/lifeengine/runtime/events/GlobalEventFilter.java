package io.lifeengine.runtime.events;

import java.util.Optional;
import java.util.UUID;

/**
 * Immutable filter applied to {@link GlobalEventStreamService#stream}. All three fields are
 * optional and combine with AND semantics. {@link #none()} returns a no-filter instance that
 * lets every runtime event through — used by the bare {@code /api/runtime/events/stream}
 * endpoint.
 */
public record GlobalEventFilter(
        Optional<String> workflowId,
        Optional<String> workflowPrefix,
        Optional<UUID> runId) {

    public static GlobalEventFilter none() {
        return new GlobalEventFilter(Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Builds a filter from query string values, treating blank strings as absent. Invalid
     * UUIDs raise {@link IllegalArgumentException} — the controller maps that to HTTP 400.
     */
    public static GlobalEventFilter from(String workflowId, String workflowPrefix, String runId) {
        return new GlobalEventFilter(
                blankToEmpty(workflowId),
                blankToEmpty(workflowPrefix),
                blankToEmpty(runId).map(UUID::fromString));
    }

    private static Optional<String> blankToEmpty(String value) {
        if (value == null) return Optional.empty();
        String trimmed = value.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }
}
