package io.lifeengine.runtime.ext.businesschat.channels;

/** Supported delivery channels for the business-chat platform. */
public enum ChannelType {
    WEB_CHAT,
    EMAIL,
    WHATSAPP,
    INSTAGRAM;

    /** Stable wire/token value used in workflow input and runtime events. */
    public String wireName() {
        return name();
    }

    public static ChannelType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidChannelTypeException(raw);
        }
        String normalized = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        for (ChannelType channel : values()) {
            if (channel.name().equals(normalized)) {
                return channel;
            }
        }
        throw new InvalidChannelTypeException(raw);
    }
}
