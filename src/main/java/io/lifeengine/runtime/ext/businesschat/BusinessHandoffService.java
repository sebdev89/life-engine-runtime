package io.lifeengine.runtime.ext.businesschat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Rule-based handoff evaluation for business-chat conversations.
 *
 * <p>Detects frustration, repeated unresolved turns, and unknown queries. Channel routing is out of
 * scope — this service only sets {@code handoffRequired=true} with an internal reason code.
 */
@Service
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BusinessHandoffService {

    public static final int MAX_FAILURES_BEFORE_HANDOFF = 2;

    private static final List<String> FRUSTRATION_SIGNALS =
            List.of(
                    "no entiend",
                    "no sirve",
                    "inutil",
                    "horrible",
                    "pesimo",
                    "mal servicio",
                    "frustrad",
                    "harto",
                    "cansado de",
                    "no ayud",
                    "no me ayud",
                    "persona real",
                    "humano",
                    "operador",
                    "asesor humano",
                    "hablar con alguien");

    private final ConcurrentMap<String, Integer> failureCounts = new ConcurrentHashMap<>();

    public HandoffDecision evaluate(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");

        boolean frustration = detectsFrustration(request.message(), request.intent());
        boolean unknownQuery = detectsUnknownQuery(request);
        boolean failureTurn = isFailureTurn(request.confidence(), unknownQuery);

        int priorFailures = failureCounts.getOrDefault(normalizeConversationId(request.conversationId()), 0);
        int failuresAfterTurn = failureTurn ? priorFailures + 1 : 0;

        HandoffReason reason = null;
        if ("human_handoff".equals(request.intent())) {
            reason = HandoffReason.HUMAN_HANDOFF;
        } else if ("emergency".equals(request.intent())) {
            reason = HandoffReason.EMERGENCY;
        } else if ("legal_sensitive".equals(request.intent())) {
            reason = HandoffReason.LEGAL_SENSITIVE;
        } else if (frustration) {
            reason = HandoffReason.FRUSTRATION;
        } else if (unknownQuery) {
            reason = HandoffReason.UNKNOWN_QUERY;
        } else if (failuresAfterTurn >= MAX_FAILURES_BEFORE_HANDOFF) {
            reason = HandoffReason.MULTIPLE_FAILURES;
        } else if (stickyHandoffAfterPriorComplaint(request.conversationHistory())) {
            reason = HandoffReason.FRUSTRATION;
        }

        boolean handoffRequired = reason != null;
        updateFailureState(request.conversationId(), failureTurn, handoffRequired);

        return new HandoffDecision(handoffRequired, reason);
    }

    int failureCount(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return 0;
        }
        return failureCounts.getOrDefault(conversationId.trim(), 0);
    }

    private void updateFailureState(String conversationId, boolean failureTurn, boolean handoffRequired) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        String key = normalizeConversationId(conversationId);
        if (handoffRequired) {
            failureCounts.remove(key);
            return;
        }
        if (failureTurn) {
            failureCounts.merge(key, 1, Integer::sum);
        } else {
            failureCounts.remove(key);
        }
    }

    private static String normalizeConversationId(String conversationId) {
        return conversationId.trim();
    }

    static boolean detectsFrustration(String message, String intent) {
        if ("complaint".equals(intent)) {
            return true;
        }
        String normalized = BusinessFaqMatcher.normalizeText(message);
        if (normalized.isBlank()) {
            return false;
        }
        for (String signal : FRUSTRATION_SIGNALS) {
            if (normalized.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    static boolean detectsUnknownQuery(EvaluationRequest request) {
        if ("greeting".equals(request.intent())
                || "human_handoff".equals(request.intent())
                || BusinessChatIntents.GUARDRAIL.contains(request.intent())) {
            return false;
        }
        if ("LOW".equals(request.confidence()) && !matchesKnowledge(request.message(), request.faqs())) {
            return true;
        }
        return "support".equals(request.intent())
                && !"HIGH".equals(request.confidence())
                && !matchesKnowledge(request.message(), request.faqs());
    }

    static boolean isFailureTurn(String confidence, boolean unknownQuery) {
        return unknownQuery || "LOW".equals(confidence);
    }

    static boolean matchesKnowledge(String message, List<BusinessBotDefinition.Faq> faqs) {
        return BusinessFaqMatcher.matchesKnowledge(message, faqs);
    }

    static boolean stickyHandoffAfterPriorComplaint(List<java.util.Map<String, String>> conversationHistory) {
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return false;
        }
        for (java.util.Map<String, String> turn : conversationHistory) {
            String customer = BusinessFaqMatcher.normalizeText(turn.get("customerMessage"));
            if (containsComplaintHistorySignal(customer)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsComplaintHistorySignal(String normalizedCustomerMessage) {
        if (normalizedCustomerMessage.isBlank()) {
            return false;
        }
        List<String> signals =
                List.of(
                        "despid",
                        "despido",
                        "desped",
                        "accidente",
                        "laboral",
                        "demanda",
                        "caso nuevo",
                        "mala experiencia",
                        "queja",
                        "denunci");
        for (String signal : signals) {
            if (normalizedCustomerMessage.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    public enum HandoffReason {
        FRUSTRATION,
        MULTIPLE_FAILURES,
        UNKNOWN_QUERY,
        HUMAN_HANDOFF,
        EMERGENCY,
        LEGAL_SENSITIVE
    }

    public record EvaluationRequest(
            String conversationId,
            String message,
            String intent,
            String confidence,
            List<BusinessBotDefinition.Faq> faqs,
            List<Map<String, String>> conversationHistory) {

        public EvaluationRequest(
                String conversationId,
                String message,
                String intent,
                String confidence,
                List<BusinessBotDefinition.Faq> faqs) {
            this(conversationId, message, intent, confidence, faqs, List.of());
        }
    }

    public record HandoffDecision(boolean handoffRequired, HandoffReason reason) {}
}
