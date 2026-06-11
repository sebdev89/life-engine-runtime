package io.lifeengine.runtime.ext.businesschat;

/** Raised when no {@link BusinessBotDefinition} is registered for the requested {@code botId}. */
public class BusinessBotNotFoundException extends RuntimeException {

    private final String botId;

    public BusinessBotNotFoundException(String botId) {
        super("Unknown botId: " + botId);
        this.botId = botId;
    }

    public String botId() {
        return botId;
    }
}
