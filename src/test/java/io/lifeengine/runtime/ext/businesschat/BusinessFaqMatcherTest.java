package io.lifeengine.runtime.ext.businesschat;

import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class BusinessFaqMatcherTest {

    private static final List<BusinessBotDefinition.Faq> BOGABOT_FAQS =
            List.of(
                    new BusinessBotDefinition.Faq(
                            "¿Cómo es la primera consulta?",
                            "La primera consulta es una reunión de 30 minutos con un abogado del área correspondiente, presencial u online según disponibilidad."),
                    new BusinessBotDefinition.Faq(
                            "¿Qué es BogaBot?",
                            "BogaBot es el asistente virtual del estudio."));

    @Test
    void correctIntent_remapsGreetingToSupportWhenFaqMatches() {
        Assertions.assertThat(
                        BusinessFaqMatcher.correctIntent(
                                "¿Cómo es la primera consulta?", "greeting", BOGABOT_FAQS))
                .isEqualTo("support");
    }

    @Test
    void correctIntent_remapsPartialFollowUpMentioningPrimeraConsulta() {
        Assertions.assertThat(
                        BusinessFaqMatcher.correctIntent(
                                "es un caso nuevo primera consulta e", "greeting", BOGABOT_FAQS))
                .isEqualTo("support");
    }

    @Test
    void correctIntent_keepsComplaintIntent() {
        Assertions.assertThat(
                        BusinessFaqMatcher.correctIntent(
                                "me despidieron despues de 5 anios", "complaint", BOGABOT_FAQS))
                .isEqualTo("complaint");
    }

    @Test
    void findBestFaq_returnsConfiguredAnswerForExactQuestion() {
        var faq =
                BusinessFaqMatcher.findBestFaq("¿Cómo es la primera consulta?", BOGABOT_FAQS)
                        .orElseThrow();
        Assertions.assertThat(faq.answer()).contains("30 minutos");
    }

    @Test
    void matchesKnowledge_isFalseForPureGreeting() {
        Assertions.assertThat(BusinessFaqMatcher.matchesKnowledge("hola", BOGABOT_FAQS)).isFalse();
    }
}
