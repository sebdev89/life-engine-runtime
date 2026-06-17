package io.lifeengine.runtime.ext.businesschat.intelligence;

import java.util.List;
import java.util.Map;

/**
 * Input to {@link BusinessKnowledgeNeedDetector}.
 *
 * <p>All fields except {@code message} are optional: the detector degrades gracefully
 * when context is absent and produces richer decisions when it is present.
 */
public record DetectionRequest(
        String tenantId,
        String botId,
        String channel,
        String message,
        String intent,
        Map<String, Object> leadFacts,
        List<Map<String, String>> conversationHistory) {

    /** Minimal factory — only bot context and message. */
    public static DetectionRequest of(String botId, String channel, String message) {
        return new DetectionRequest(null, botId, channel, message, null, null, null);
    }

    /** Factory including pre-classified intent (e.g. from a prior stage). */
    public static DetectionRequest withIntent(
            String botId, String channel, String message, String intent) {
        return new DetectionRequest(null, botId, channel, message, intent, null, null);
    }

    /** Full factory for all available context fields. */
    public static DetectionRequest full(
            String tenantId,
            String botId,
            String channel,
            String message,
            String intent,
            Map<String, Object> leadFacts,
            List<Map<String, String>> conversationHistory) {
        return new DetectionRequest(
                tenantId, botId, channel, message, intent, leadFacts, conversationHistory);
    }
}
