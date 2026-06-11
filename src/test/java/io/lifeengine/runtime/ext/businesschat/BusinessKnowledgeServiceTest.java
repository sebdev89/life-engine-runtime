package io.lifeengine.runtime.ext.businesschat;

import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BusinessKnowledgeServiceTest {

    private BusinessKnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        BusinessBotRegistry registry = new BusinessBotRegistry();
        registry.registerDefaults();
        knowledgeService = new BusinessKnowledgeService(registry);
    }

    @Test
    void require_buildsKnowledgeBaseForBarberiaDemo() {
        BusinessKnowledgeService.KnowledgeBase knowledge = knowledgeService.require("barberia-demo");

        Assertions.assertThat(knowledge.botId()).isEqualTo("barberia-demo");
        Assertions.assertThat(knowledge.businessName()).isEqualTo("Barbería Demo");
        Assertions.assertThat(knowledge.rules()).isNotEmpty();
        Assertions.assertThat(knowledge.faqs()).hasSize(5);
        Assertions.assertThat(knowledge.text()).contains("Corte + barba: $12000");
    }

    @Test
    void profileForLlm_includesStructuredFieldsAndRenderedText() {
        BusinessKnowledgeService.KnowledgeBase knowledge = knowledgeService.require("inmobiliaria-demo");

        var profile = knowledgeService.profileForLlm(knowledge);

        Assertions.assertThat(profile.get("businessName")).isEqualTo("Inmobiliaria Demo");
        Assertions.assertThat(profile.get("knowledgeBase")).isEqualTo(knowledge.text());
        Assertions.assertThat(profile.get("faqs")).isEqualTo(knowledge.faqs());
        Assertions.assertThat(knowledge.text()).contains("USD 120.000");
    }

    @Test
    void require_rejectsUnknownBotId() {
        Assertions.assertThatThrownBy(() -> knowledgeService.require("unknown"))
                .isInstanceOf(BusinessBotNotFoundException.class);
    }

    @Test
    void resolve_includesRetrievedChunksInKnowledgeText() {
        BusinessChatReplyIo.BusinessContext context =
                new BusinessChatReplyIo.BusinessContext(
                        "Relojes Importados",
                        "ecommerce",
                        "Vendedor cercano",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(
                                new BusinessChatReplyIo.RetrievedChunk(
                                        "doc-1",
                                        "chunk-1",
                                        "Garantía importados",
                                        "12 meses de garantía en relojes importados premium.",
                                        0.88)));

        BusinessKnowledgeService.KnowledgeBase knowledge =
                knowledgeService.resolve("relojes-importados", context);

        Assertions.assertThat(knowledge.text()).contains("Fragmentos recuperados");
        Assertions.assertThat(knowledge.text()).contains("12 meses de garantía");
        Assertions.assertThat(knowledge.retrievedChunks()).hasSize(1);
    }
}
