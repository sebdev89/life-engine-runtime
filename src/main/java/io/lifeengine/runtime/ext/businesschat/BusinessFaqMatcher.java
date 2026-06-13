package io.lifeengine.runtime.ext.businesschat;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic FAQ overlap for intent correction (R-1) and direct FAQ replies (R-2).
 */
public final class BusinessFaqMatcher {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9\\s]");
    private static final int MIN_FAQ_MATCH_SCORE = 2;

    private BusinessFaqMatcher() {}

    /** Remap greeting/unclear to support when configured FAQs cover the customer message. */
    public static String correctIntent(
            String message, String llmIntent, List<BusinessBotDefinition.Faq> faqs) {
        if (llmIntent == null || llmIntent.isBlank()) {
            return llmIntent;
        }
        if (!"greeting".equals(llmIntent) && !"unclear".equals(llmIntent)) {
            return llmIntent;
        }
        if (matchesKnowledge(message, faqs)) {
            return "support";
        }
        return llmIntent;
    }

    public static boolean matchesKnowledge(String message, List<BusinessBotDefinition.Faq> faqs) {
        return findBestFaq(message, faqs).isPresent();
    }

    public static boolean shouldAnswerFromFaq(String message, List<BusinessBotDefinition.Faq> faqs) {
        return findBestFaq(message, faqs).isPresent();
    }

    public static Optional<BusinessBotDefinition.Faq> findBestFaq(
            String message, List<BusinessBotDefinition.Faq> faqs) {
        if (faqs == null || faqs.isEmpty()) {
            return Optional.empty();
        }
        String normalizedMessage = normalizeText(message);
        if (normalizedMessage.isBlank()) {
            return Optional.empty();
        }

        BusinessBotDefinition.Faq best = null;
        int bestScore = 0;
        for (BusinessBotDefinition.Faq faq : faqs) {
            int score = scoreFaqMatch(normalizedMessage, normalizeText(faq.question()));
            if (score > bestScore) {
                bestScore = score;
                best = faq;
            }
        }
        return bestScore >= MIN_FAQ_MATCH_SCORE ? Optional.ofNullable(best) : Optional.empty();
    }

    static int scoreFaqMatch(String normalizedMessage, String normalizedQuestion) {
        if (normalizedQuestion.isBlank()) {
            return 0;
        }
        if (normalizedMessage.contains(normalizedQuestion)
                || normalizedQuestion.contains(normalizedMessage)) {
            return Math.max(normalizedMessage.length(), normalizedQuestion.length());
        }
        int tokens = 0;
        for (String token : normalizedQuestion.split("\\s+")) {
            if (token.length() >= 4 && normalizedMessage.contains(token)) {
                tokens++;
            }
        }
        return tokens;
    }

    static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized =
                Normalizer.normalize(value, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}+", "")
                        .toLowerCase(Locale.ROOT);
        return NON_ALPHANUMERIC.matcher(normalized).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }
}
