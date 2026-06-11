package io.lifeengine.runtime.api;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** Single observability event appended to an existing run (e.g. from business-chat-service). */
public record AppendRunEventRequest(
        @NotBlank String type, Map<String, String> attributes, Boolean terminal) {

    public AppendRunEventRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
