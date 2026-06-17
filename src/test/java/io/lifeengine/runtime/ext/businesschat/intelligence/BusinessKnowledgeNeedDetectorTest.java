package io.lifeengine.runtime.ext.businesschat.intelligence;

import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sprint 6 — 24 deterministic test cases for {@link BusinessKnowledgeNeedDetector}.
 *
 * <p>Cases are grouped by expected strategy: NONE (8), RAG_ONLY (7), SEARCH_ONLY (5), HYBRID (4).
 */
class BusinessKnowledgeNeedDetectorTest {

    private BusinessKnowledgeNeedDetector detector;

    @BeforeEach
    void setUp() {
        detector = new BusinessKnowledgeNeedDetector();
    }

    // ── NONE cases (8) ────────────────────────────────────────────────────────

    @Test
    void none_emptyMessage() {
        KnowledgeNeedDetection r = detector.detect(DetectionRequest.of("bot-1", "WEB_CHAT", ""));
        assertStrategy(r, KnowledgeNeedStrategy.NONE);
        Assertions.assertThat(r.reason()).isEqualTo("empty_message");
    }

    @Test
    void none_pureGreeting_hola() {
        KnowledgeNeedDetection r = detector.detect(DetectionRequest.of("bot-1", "WEB_CHAT", "Hola"));
        assertNone(r);
    }

    @Test
    void none_greeting_buenosDias() {
        KnowledgeNeedDetection r = detector.detect(DetectionRequest.of("bot-1", "WEB_CHAT", "Buenos días"));
        assertNone(r);
    }

    @Test
    void none_acknowledgement_gracias() {
        KnowledgeNeedDetection r = detector.detect(DetectionRequest.of("bot-1", "WEB_CHAT", "Gracias"));
        assertNone(r);
    }

    @Test
    void none_acknowledgement_perfecto() {
        KnowledgeNeedDetection r = detector.detect(DetectionRequest.of("bot-1", "WEB_CHAT", "Perfecto"));
        assertNone(r);
    }

    @Test
    void none_confirmation_confirmado() {
        KnowledgeNeedDetection r = detector.detect(DetectionRequest.of("bot-1", "WEB_CHAT", "Confirmado"));
        assertNone(r);
    }

    @Test
    void none_farewell_chau() {
        KnowledgeNeedDetection r = detector.detect(DetectionRequest.of("bot-1", "WEB_CHAT", "Chau"));
        assertNone(r);
    }

    @Test
    void none_humanHandoffIntent_shortcutToNone() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.withIntent("bot-1", "WEB_CHAT",
                        "Necesito hablar con alguien", "human_handoff"));
        assertStrategy(r, KnowledgeNeedStrategy.NONE);
        Assertions.assertThat(r.reason()).isEqualTo("human_handoff_intent");
    }

    // ── RAG_ONLY cases (7) ────────────────────────────────────────────────────

    @Test
    void ragOnly_pricingQuestion() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT", "¿Cuánto sale un corte de pelo?"));
        assertStrategy(r, KnowledgeNeedStrategy.RAG_ONLY);
        Assertions.assertThat(r.ragQuery()).isNotBlank();
        Assertions.assertThat(r.searchQuery()).isBlank();
    }

    @Test
    void ragOnly_scheduleQuestion() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT", "¿Cuáles son sus horarios de atención?"));
        assertStrategy(r, KnowledgeNeedStrategy.RAG_ONLY);
    }

    @Test
    void ragOnly_servicesQuestion() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT", "¿Qué servicios ofrecen?"));
        assertStrategy(r, KnowledgeNeedStrategy.RAG_ONLY);
    }

    @Test
    void ragOnly_warrantyQuestion() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT", "¿Tienen garantía en los productos?"));
        assertStrategy(r, KnowledgeNeedStrategy.RAG_ONLY);
    }

    @Test
    void ragOnly_locationQuestion() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT", "¿Dónde están ubicados?"));
        assertStrategy(r, KnowledgeNeedStrategy.RAG_ONLY);
    }

    @Test
    void ragOnly_legalCaseQuestion() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT", "¿Manejan casos de despido laboral?"));
        assertStrategy(r, KnowledgeNeedStrategy.RAG_ONLY);
    }

    @Test
    void ragOnly_activeLead_refinesToRag() {
        // Even without explicit RAG signals, an active lead pushes toward RAG
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.full(null, "bot-1", "WEB_CHAT",
                        "La antigüedad fue de 5 años",
                        null,
                        Map.of("caseType", "despido"),
                        null));
        assertStrategy(r, KnowledgeNeedStrategy.RAG_ONLY);
    }

    // ── SEARCH_ONLY cases (5) ─────────────────────────────────────────────────

    @Test
    void searchOnly_currentDollarPrice() {
        // "cuánto" + "vale" trigger RAG pricing signals; "dólar hoy" triggers SEARCH → HYBRID.
        // A business may have internal exchange-rate info AND need live market data.
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT", "¿Cuánto vale el dólar hoy?"));
        assertStrategy(r, KnowledgeNeedStrategy.HYBRID);
    }

    @Test
    void searchOnly_laborLawChange() {
        // "laboral" triggers RAG professional signal; "cambió la ley / este año" trigger SEARCH.
        // A law firm needs both internal case KB and current legal-change news → HYBRID.
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT", "¿Cambió la ley laboral este año?"));
        assertStrategy(r, KnowledgeNeedStrategy.HYBRID);
    }

    @Test
    void searchOnly_latestNews() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT",
                        "¿Cuáles son las últimas noticias sobre el mercado financiero?"));
        assertStrategy(r, KnowledgeNeedStrategy.SEARCH_ONLY);
    }

    @Test
    void searchOnly_recentNormativa() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT",
                        "¿Hubo reformas recientes en la normativa?"));
        assertStrategy(r, KnowledgeNeedStrategy.SEARCH_ONLY);
    }

    @Test
    void searchOnly_greetingIntentShortcuts() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.withIntent("bot-1", "WEB_CHAT", "Hola buen día", "greeting"));
        assertStrategy(r, KnowledgeNeedStrategy.NONE);
        Assertions.assertThat(r.reason()).isEqualTo("greeting_intent");
    }

    // ── HYBRID cases (4) ──────────────────────────────────────────────────────

    @Test
    void hybrid_rolexPriceToday() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT",
                        "¿Cuánto cuesta un Rolex hoy y tienen servicio técnico?"));
        assertStrategy(r, KnowledgeNeedStrategy.HYBRID);
        Assertions.assertThat(r.ragQuery()).isNotBlank();
        Assertions.assertThat(r.searchQuery()).isNotBlank();
    }

    @Test
    void hybrid_laborLawAndCases() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT",
                        "¿Cambiaron la ley laboral y manejan casos de despido?"));
        assertStrategy(r, KnowledgeNeedStrategy.HYBRID);
    }

    @Test
    void hybrid_shippingAndCurrentMarket() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT",
                        "¿Envían a Córdoba y cuánto es el precio actual en el mercado?"));
        assertStrategy(r, KnowledgeNeedStrategy.HYBRID);
    }

    @Test
    void hybrid_servicesAndLegalNovedades() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT",
                        "¿Qué servicios tienen y cuáles son las últimas novedades legales?"));
        assertStrategy(r, KnowledgeNeedStrategy.HYBRID);
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test
    void edge_nullRequest_returnsNone() {
        KnowledgeNeedDetection r = detector.detect(null);
        assertStrategy(r, KnowledgeNeedStrategy.NONE);
    }

    @Test
    void edge_multiWordAck_muchasGracias() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT", "Muchas gracias"));
        assertNone(r);
    }

    @Test
    void edge_shortNoSignal_returnsNone() {
        KnowledgeNeedDetection r = detector.detect(
                DetectionRequest.of("bot-1", "WEB_CHAT", "No entendí"));
        assertNone(r);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void assertStrategy(KnowledgeNeedDetection r, KnowledgeNeedStrategy expected) {
        Assertions.assertThat(r).isNotNull();
        Assertions.assertThat(r.strategy())
                .as("Expected strategy %s but got %s (reason: %s)", expected, r.strategy(), r.reason())
                .isEqualTo(expected);
        Assertions.assertThat(r.reason()).isNotBlank();
    }

    private static void assertNone(KnowledgeNeedDetection r) {
        assertStrategy(r, KnowledgeNeedStrategy.NONE);
        Assertions.assertThat(r.ragQuery()).isBlank();
        Assertions.assertThat(r.searchQuery()).isBlank();
    }
}
