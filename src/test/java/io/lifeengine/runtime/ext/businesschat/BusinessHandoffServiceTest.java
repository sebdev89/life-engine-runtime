package io.lifeengine.runtime.ext.businesschat;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BusinessHandoffServiceTest {

    private BusinessHandoffService handoffService;
    private BusinessKnowledgeService.KnowledgeBase barberiaKnowledge;

    @BeforeEach
    void setUp() {
        BusinessBotRegistry registry = new BusinessBotRegistry();
        registry.registerDefaults();
        barberiaKnowledge = new BusinessKnowledgeService(registry).require("barberia-demo");
        handoffService = new BusinessHandoffService();
    }

    @Test
    void evaluate_detectsFrustrationFromMessage() {
        assertHandoff(
                handoffService.evaluate(
                        request(
                                "conv-1",
                                "Esto no sirve, quiero hablar con una persona real",
                                "support",
                                "MEDIUM")),
                BusinessHandoffService.HandoffReason.FRUSTRATION);
    }

    @Test
    void evaluate_detectsFrustrationFromComplaintIntent() {
        assertHandoff(
                handoffService.evaluate(
                        request("conv-1", "Tuve una mala experiencia ayer", "complaint", "HIGH")),
                BusinessHandoffService.HandoffReason.FRUSTRATION);
    }

    @Test
    void evaluate_detectsUnknownQuery() {
        assertHandoff(
                handoffService.evaluate(
                        request(
                                "conv-1",
                                "¿Hacen delivery intergaláctico?",
                                "support",
                                "LOW")),
                BusinessHandoffService.HandoffReason.UNKNOWN_QUERY);
    }

    @Test
    void evaluate_detectsMultipleFailuresAfterRepeatedLowConfidence() {
        var first =
                handoffService.evaluate(
                        request("conv-fail", "¿Cuánto sale un corte?", "pricing", "LOW"));
        Assertions.assertThat(first.handoffRequired()).isFalse();
        Assertions.assertThat(handoffService.failureCount("conv-fail")).isEqualTo(1);

        assertHandoff(
                handoffService.evaluate(
                        request("conv-fail", "¿Cuánto sale la barba?", "pricing", "LOW")),
                BusinessHandoffService.HandoffReason.MULTIPLE_FAILURES);
    }

    @Test
    void evaluate_resetsFailuresAfterKnownPricingQuery() {
        handoffService.evaluate(request("conv-reset", "¿Cuánto sale un corte?", "pricing", "LOW"));
        Assertions.assertThat(handoffService.failureCount("conv-reset")).isEqualTo(1);

        var recovered =
                handoffService.evaluate(
                        request("conv-reset", "¿Cuánto sale un corte?", "pricing", "HIGH"));
        Assertions.assertThat(recovered.handoffRequired()).isFalse();
        Assertions.assertThat(handoffService.failureCount("conv-reset")).isZero();
    }

    @Test
    void evaluate_detectsHumanHandoffIntent() {
        assertHandoff(
                handoffService.evaluate(
                        request("conv-1", "Pasame con un humano", "human_handoff", "HIGH")),
                BusinessHandoffService.HandoffReason.HUMAN_HANDOFF);
    }

    private BusinessHandoffService.EvaluationRequest request(
            String conversationId, String message, String intent, String confidence) {
        return new BusinessHandoffService.EvaluationRequest(
                conversationId, message, intent, confidence, barberiaKnowledge.faqs());
    }

    private static void assertHandoff(
            BusinessHandoffService.HandoffDecision decision,
            BusinessHandoffService.HandoffReason reason) {
        Assertions.assertThat(decision.handoffRequired()).isTrue();
        Assertions.assertThat(decision.reason()).isEqualTo(reason);
    }
}
