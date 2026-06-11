package io.lifeengine.runtime.ext.businesschat.channels;

/** Raised when a channel value is missing or not a supported {@link ChannelType}. */
public class InvalidChannelTypeException extends RuntimeException {

    private final String channel;

    public InvalidChannelTypeException(String channel) {
        super("Unsupported channel: " + (channel == null || channel.isBlank() ? "<empty>" : channel.trim()));
        this.channel = channel;
    }

    public String channel() {
        return channel;
    }
}
