package io.lifeengine.runtime.ext.businesschat;

import java.util.List;

/**
 * Configuration for a business-chat bot. Resolved by {@code botId} across channels (web, WhatsApp,
 * email, etc.).
 */
public record BusinessBotDefinition(
        String botId,
        String businessName,
        String tone,
        List<String> rules,
        List<Faq> faqs,
        List<SuggestedPrompt> suggestedPrompts) {

    public record Faq(String question, String answer) {}

    /** Quick-reply chip shown in web chat; {@code message} is sent to the workflow. */
    public record SuggestedPrompt(String label, String message) {}
}
