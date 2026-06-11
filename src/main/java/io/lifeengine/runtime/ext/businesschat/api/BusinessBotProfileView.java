package io.lifeengine.runtime.ext.businesschat.api;

import java.util.List;

/** Read-only business profile exposed to business-chat clients. */
public record BusinessBotProfileView(
        String botId,
        String businessName,
        String tone,
        List<String> rules,
        List<FaqView> faqs,
        List<SuggestedPromptView> suggestedPrompts) {

    public record FaqView(String question, String answer) {}

    public record SuggestedPromptView(String label, String message) {}
}
