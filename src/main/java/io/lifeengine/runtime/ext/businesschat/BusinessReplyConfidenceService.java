package io.lifeengine.runtime.ext.businesschat;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Deterministic reply confidence — no ML, based on intent, knowledge config, and message quality. */
@Service
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BusinessReplyConfidenceService {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9\\s?]");

    private static final List<String> LEGAL_SENSITIVE_SIGNALS =
            List.of(
                    "me conviene demandar",
                    "conviene demandar",
                    "debo demandar",
                    "deberia demandar",
                    "debería demandar",
                    "puedo demandar",
                    "vale la pena demandar",
                    "me conviene denunciar",
                    "debo denunciar",
                    "deberia denunciar",
                    "debería denunciar");

    private static final List<String> FRUSTRATION_SIGNALS =
            List.of(
                    "no me sirve",
                    "no me ayuda",
                    "no entiend",
                    "inutil",
                    "pesimo",
                    "mal servicio",
                    "no sirve tu respuesta");

    public ReplyConfidence evaluate(
            String message,
            List<Map<String, String>> conversationHistory,
            String intent,
            List<BusinessBotDefinition.Faq> faqs,
            List<Map<String, String>> catalogItems,
            String guardrailIntent) {
        String normalized = normalize(message);
        String resolvedIntent = normalizeIntent(intent, guardrailIntent);

        ReplyConfidence guardrailConfidence = confidenceForGuardrail(guardrailIntent);
        if (guardrailConfidence != null) {
            return guardrailConfidence;
        }
        if (isGibberish(normalized)) {
            return low("mensaje_ambiguo");
        }
        if (isLegalSensitive(normalized)) {
            return low("pregunta_sensible");
        }
        if (isConflictTurn(normalized, resolvedIntent, conversationHistory)) {
            return low("conflicto_usuario");
        }

        if (isFollowUpWithContext(normalized, conversationHistory)) {
            return medium("contexto_resuelve_seguimiento");
        }

        if (isHighConfidenceKnowledgeTurn(resolvedIntent, normalized, faqs, catalogItems)) {
            return high(knowledgeReason(resolvedIntent));
        }
        if ("greeting".equals(resolvedIntent) && isFirstTurn(conversationHistory)) {
            return high("intent_claro");
        }
        if (isIncompleteMessage(normalized) && hasProbableIntent(resolvedIntent)) {
            return medium("mensaje_incompleto");
        }
        if (hasPartialKnowledge(resolvedIntent, faqs, catalogItems)) {
            return medium("respuesta_parcial_config");
        }
        if (hasProbableIntent(resolvedIntent)) {
            return medium("intent_probable");
        }

        return low("datos_insuficientes");
    }

    private static ReplyConfidence confidenceForGuardrail(String guardrailIntent) {
        if (guardrailIntent == null || guardrailIntent.isBlank()) {
            return null;
        }
        return switch (guardrailIntent) {
            case "out_of_domain" -> low("fuera_de_dominio");
            case "unclear" -> low("mensaje_ambiguo");
            case "legal_sensitive", "emergency" -> low("pregunta_sensible");
            case "abusive" -> low("conflicto_usuario");
            default -> null;
        };
    }

    private static boolean isHighConfidenceKnowledgeTurn(
            String intent,
            String normalizedMessage,
            List<BusinessBotDefinition.Faq> faqs,
            List<Map<String, String>> catalogItems) {
        if (!hasProbableIntent(intent)) {
            return false;
        }
        return switch (intent) {
            case "schedule" -> hasScheduleKnowledge(faqs, catalogItems);
            case "pricing" -> hasPricingKnowledge(faqs, catalogItems);
            case "location" -> hasLocationKnowledge(faqs, catalogItems);
            default -> BusinessFaqMatcher.matchesKnowledge(normalizedMessage, faqs);
        };
    }

    private static String knowledgeReason(String intent) {
        return switch (intent) {
            case "schedule" -> "respuesta_desde_config_horario";
            case "pricing" -> "respuesta_desde_config_precio";
            case "location" -> "respuesta_desde_config_ubicacion";
            default -> "respuesta_desde_config_faq";
        };
    }

    private static boolean hasPartialKnowledge(
            String intent, List<BusinessBotDefinition.Faq> faqs, List<Map<String, String>> catalogItems) {
        if (faqs == null || faqs.isEmpty()) {
            return catalogItems != null && !catalogItems.isEmpty();
        }
        return switch (intent) {
            case "schedule" -> !hasScheduleKnowledge(faqs, catalogItems);
            case "pricing" -> !hasPricingKnowledge(faqs, catalogItems);
            case "location" -> !hasLocationKnowledge(faqs, catalogItems);
            default -> false;
        };
    }

    private static boolean hasScheduleKnowledge(
            List<BusinessBotDefinition.Faq> faqs, List<Map<String, String>> catalogItems) {
        return containsKnowledgeSignal(faqs, catalogItems, "horario", "horarios", "atencion", "lunes", "viernes");
    }

    private static boolean hasPricingKnowledge(
            List<BusinessBotDefinition.Faq> faqs, List<Map<String, String>> catalogItems) {
        if (catalogItems != null) {
            for (Map<String, String> item : catalogItems) {
                String price = item.getOrDefault("priceText", "");
                if (!price.isBlank()) {
                    return true;
                }
            }
        }
        return containsKnowledgeSignal(
                faqs, catalogItems, "precio", "cuesta", "sale", "$", "ars", "usd", "valor", "tarifa", "honorario");
    }

    private static boolean hasLocationKnowledge(
            List<BusinessBotDefinition.Faq> faqs, List<Map<String, String>> catalogItems) {
        return containsKnowledgeSignal(
                faqs, catalogItems, "ubicad", "direccion", "donde", "calle", "avenida", "av ", "caba");
    }

    private static boolean containsKnowledgeSignal(
            List<BusinessBotDefinition.Faq> faqs,
            List<Map<String, String>> catalogItems,
            String... signals) {
        String corpus = knowledgeCorpus(faqs, catalogItems);
        for (String signal : signals) {
            if (corpus.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    private static String knowledgeCorpus(
            List<BusinessBotDefinition.Faq> faqs, List<Map<String, String>> catalogItems) {
        StringBuilder sb = new StringBuilder();
        if (faqs != null) {
            for (BusinessBotDefinition.Faq faq : faqs) {
                sb.append(normalize(faq.question())).append(' ').append(normalize(faq.answer())).append(' ');
            }
        }
        if (catalogItems != null) {
            for (Map<String, String> item : catalogItems) {
                item.values().forEach(value -> sb.append(normalize(value)).append(' '));
            }
        }
        return sb.toString();
    }

    private static boolean isFollowUpWithContext(
            String normalizedMessage, List<Map<String, String>> conversationHistory) {
        if (!isFollowUp(normalizedMessage)) {
            return false;
        }
        return conversationHistory != null && !conversationHistory.isEmpty();
    }

    private static boolean isFollowUp(String normalizedMessage) {
        if (normalizedMessage.startsWith("y ")
                || normalizedMessage.startsWith("y,")
                || normalizedMessage.equals("y")) {
            return true;
        }
        if (normalizedMessage.contains("cuanto dura") || normalizedMessage.contains("cuanto tarda")) {
            return normalizedMessage.startsWith("y ") || normalizedMessage.length() <= 30;
        }
        return normalizedMessage.length() <= 20
                && (normalizedMessage.contains("dura")
                        || normalizedMessage.contains("tarda")
                        || normalizedMessage.contains("incluye"));
    }

    private static boolean isIncompleteMessage(String normalizedMessage) {
        return normalizedMessage.length() < 18
                && !normalizedMessage.contains("horario")
                && !normalizedMessage.contains("precio")
                && !normalizedMessage.contains("cuesta");
    }

    private static boolean hasProbableIntent(String intent) {
        return intent != null
                && !intent.isBlank()
                && !"support".equals(intent)
                && !BusinessChatIntents.GUARDRAIL.contains(intent);
    }

    private static boolean isConflictTurn(
            String normalizedMessage, String intent, List<Map<String, String>> conversationHistory) {
        if ("complaint".equals(intent)) {
            return true;
        }
        int frustrationCount = 0;
        if (conversationHistory != null) {
            for (Map<String, String> turn : conversationHistory) {
                if (containsFrustration(normalize(turn.getOrDefault("customerMessage", "")))) {
                    frustrationCount++;
                }
            }
        }
        if (containsFrustration(normalizedMessage)) {
            frustrationCount++;
        }
        return frustrationCount >= 2;
    }

    private static boolean containsFrustration(String normalizedMessage) {
        for (String signal : FRUSTRATION_SIGNALS) {
            if (normalizedMessage.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLegalSensitive(String normalizedMessage) {
        for (String signal : LEGAL_SENSITIVE_SIGNALS) {
            if (normalizedMessage.contains(normalize(signal))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGibberish(String normalizedMessage) {
        if (normalizedMessage.isBlank() || normalizedMessage.contains("?") || normalizedMessage.length() < 8) {
            return false;
        }
        String[] tokens = normalizedMessage.split("\\s+");
        for (String token : tokens) {
            if (token.length() >= 4 && looksRecognizable(token)) {
                return false;
            }
        }
        return tokens.length > 0;
    }

    private static boolean looksRecognizable(String token) {
        long vowels = token.chars().filter(ch -> "aeiou".indexOf(ch) >= 0).count();
        if (vowels == 0) {
            return false;
        }
        double ratio = (double) vowels / token.length();
        return ratio >= 0.2 && ratio <= 0.6 && token.chars().distinct().count() >= 4;
    }

    private static boolean isFirstTurn(List<Map<String, String>> conversationHistory) {
        return conversationHistory == null || conversationHistory.isEmpty();
    }

    private static String normalizeIntent(String intent, String guardrailIntent) {
        if (guardrailIntent != null && !guardrailIntent.isBlank()) {
            return guardrailIntent.trim().toLowerCase(Locale.ROOT);
        }
        if (intent == null || intent.isBlank()) {
            return "";
        }
        return intent.trim().toLowerCase(Locale.ROOT);
    }

    private static ReplyConfidence high(String reason) {
        return new ReplyConfidence("HIGH", reason);
    }

    private static ReplyConfidence medium(String reason) {
        return new ReplyConfidence("MEDIUM", reason);
    }

    private static ReplyConfidence low(String reason) {
        return new ReplyConfidence("LOW", reason);
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized =
                Normalizer.normalize(value, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}+", "")
                        .toLowerCase(Locale.ROOT);
        return NON_ALPHANUMERIC.matcher(normalized).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    public record ReplyConfidence(String level, String reason) {
        public ReplyConfidence {
            level = level == null ? "LOW" : level.trim().toUpperCase(Locale.ROOT);
            reason = reason == null || reason.isBlank() ? "sin_motivo" : reason.trim();
        }
    }
}
