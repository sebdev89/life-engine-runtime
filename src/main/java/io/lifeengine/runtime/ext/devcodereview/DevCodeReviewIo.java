package io.lifeengine.runtime.ext.devcodereview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Workflow-input codec for {@link DevCodeReviewModule#WORKFLOW_ID}.
 */
public final class DevCodeReviewIo {

    private DevCodeReviewIo() {}

    public record Input(String language, String code) {}

    public static Input readInput(ObjectMapper mapper, String raw) throws JsonProcessingException {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("input is required: {\"code\":\"...\",\"language\":\"...\"}");
        }
        JsonNode root = mapper.readTree(raw);
        if (!root.isObject()) {
            throw new IllegalArgumentException("input must be a JSON object with code and language");
        }
        String code = textOrBlank(root, "code");
        String language = textOrBlank(root, "language");
        if (code.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: code");
        }
        if (language.isBlank()) {
            language = "unknown";
        }
        return new Input(language.trim(), code);
    }

    private static String textOrBlank(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return "";
        }
        return value.asText();
    }
}
