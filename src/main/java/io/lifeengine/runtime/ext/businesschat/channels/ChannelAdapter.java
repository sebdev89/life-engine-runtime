package io.lifeengine.runtime.ext.businesschat.channels;

/**
 * Contract for a channel-specific ingress/egress adapter.
 *
 * <p>Implementations live outside the runtime spine and are responsible for parsing native provider
 * payloads into {@link BusinessChatInboundMessage} and formatting {@link BusinessChatOutboundMessage}
 * for delivery. No adapter ships in this module yet.
 */
public interface ChannelAdapter {

    /** Channel handled by this adapter. */
    ChannelType channelType();

    /**
     * Parses a native provider payload into the canonical inbound message consumed by
     * {@code business-chat.reply.v1}.
     */
    BusinessChatInboundMessage parseInbound(String nativePayload);

    /**
     * Formats a workflow reply for delivery on this channel. The returned string is the
     * provider-native payload (JSON, MIME body, etc.) — encoding is adapter-specific.
     */
    String formatOutbound(BusinessChatOutboundMessage reply);
}
