package io.lifeengine.runtime.ext.businesschat;

import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BusinessReplyConfidenceServiceTest {

    private BusinessReplyConfidenceService service;
    private List<BusinessBotDefinition.Faq> barberiaFaqs;

    @BeforeEach
    void setUp() {
        service = new BusinessReplyConfidenceService();
        barberiaFaqs =
                List.of(
                        new BusinessBotDefinition.Faq("¿Cuánto sale un corte?", "Corte: $8000"),
                        new BusinessBotDefinition.Faq(
                                "¿Cuáles son los horarios?",
                                "Lunes a viernes de 10 a 20. Sábados de 10 a 16."));
    }

    @Test
    void scheduleQuestionWithConfiguredKnowledge_isHigh() {
        BusinessReplyConfidenceService.ReplyConfidence confidence =
                service.evaluate(
                        "¿Cuál es el horario?",
                        List.of(),
                        "schedule",
                        barberiaFaqs,
                        List.of(),
                        null);

        Assertions.assertThat(confidence.level()).isEqualTo("HIGH");
        Assertions.assertThat(confidence.reason()).isEqualTo("respuesta_desde_config_horario");
    }

    @Test
    void pricingQuestionWithConfiguredKnowledge_isHigh() {
        BusinessReplyConfidenceService.ReplyConfidence confidence =
                service.evaluate(
                        "¿Cuánto cuesta?",
                        List.of(),
                        "pricing",
                        barberiaFaqs,
                        List.of(),
                        null);

        Assertions.assertThat(confidence.level()).isEqualTo("HIGH");
        Assertions.assertThat(confidence.reason()).isEqualTo("respuesta_desde_config_precio");
    }

    @Test
    void followUpWithContext_isMedium() {
        List<Map<String, String>> history =
                List.of(
                        Map.of(
                                "customerMessage", "quiero una consulta laboral",
                                "botResponse", "Te cuento sobre la consulta laboral."));

        BusinessReplyConfidenceService.ReplyConfidence confidence =
                service.evaluate(
                        "¿Y eso cuánto tarda?",
                        history,
                        "schedule",
                        barberiaFaqs,
                        List.of(),
                        null);

        Assertions.assertThat(confidence.level()).isEqualTo("MEDIUM");
        Assertions.assertThat(confidence.reason()).isEqualTo("contexto_resuelve_seguimiento");
    }

    @Test
    void gibberish_isLow() {
        BusinessReplyConfidenceService.ReplyConfidence confidence =
                service.evaluate("asdasd qweqwe", List.of(), "support", List.of(), List.of(), "unclear");

        Assertions.assertThat(confidence.level()).isEqualTo("LOW");
        Assertions.assertThat(confidence.reason()).isEqualTo("mensaje_ambiguo");
    }

    @Test
    void legalSensitiveQuestion_isLow() {
        BusinessReplyConfidenceService.ReplyConfidence confidence =
                service.evaluate(
                        "¿Me conviene demandar?",
                        List.of(),
                        "legal_sensitive",
                        barberiaFaqs,
                        List.of(),
                        "legal_sensitive");

        Assertions.assertThat(confidence.level()).isEqualTo("LOW");
        Assertions.assertThat(confidence.reason()).isEqualTo("pregunta_sensible");
    }
}
