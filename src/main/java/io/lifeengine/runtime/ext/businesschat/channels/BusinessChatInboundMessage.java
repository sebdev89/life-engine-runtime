package io.lifeengine.runtime.ext.businesschat.channels;

import io.lifeengine.runtime.ext.businesschat.BusinessChatReplyIo;
import java.util.Objects;

/**
 * Channel-agnostic inbound message accepted by the business-chat spine before workflow execution.
 *
 * <p>Future {@link ChannelAdapter} implementations map native provider payloads into this shape.
 */
public record BusinessChatInboundMessage(
        ChannelType channel,
        String botId,
        String conversationId,
        String customerName,
        String customerExternalId,
        String message) {

    public BusinessChatInboundMessage {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(customerName, "customerName");
        Objects.requireNonNull(customerExternalId, "customerExternalId");
        Objects.requireNonNull(message, "message");
        if (botId.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: botId");
        }
        if (conversationId.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: conversationId");
        }
        if (customerName.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: customerName");
        }
        if (customerExternalId.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: customerExternalId");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: message");
        }
    }

    public BusinessChatReplyIo.Input toWorkflowInput() {
        return new BusinessChatReplyIo.Input(
                channel.wireName(),
                botId.trim(),
                conversationId.trim(),
                new BusinessChatReplyIo.Customer(customerName.trim(), customerExternalId.trim()),
                message.trim(),
                null);
    }

    public static BusinessChatInboundMessage fromWorkflowInput(BusinessChatReplyIo.Input input) {
        Objects.requireNonNull(input, "input");
        return new BusinessChatInboundMessage(
                ChannelType.parse(input.channel()),
                input.botId(),
                input.conversationId(),
                input.customer().name(),
                input.customer().externalId(),
                input.message());
    }
}
