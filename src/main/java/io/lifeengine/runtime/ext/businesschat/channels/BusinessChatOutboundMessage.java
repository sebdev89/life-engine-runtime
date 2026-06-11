package io.lifeengine.runtime.ext.businesschat.channels;

import java.util.Objects;

/**
 * Channel-agnostic outbound reply produced by the business-chat workflow.
 *
 * <p>Future {@link ChannelAdapter} implementations translate this into provider-native payloads.
 */
public record BusinessChatOutboundMessage(
        ChannelType channel,
        String botId,
        String conversationId,
        String customerExternalId,
        String response,
        String intent,
        String confidence,
        boolean handoffRequired,
        boolean leadCaptured) {

    public BusinessChatOutboundMessage {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(customerExternalId, "customerExternalId");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(confidence, "confidence");
        if (response.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: response");
        }
    }
}
