package io.lifeengine.runtime.ext.businesschat;

import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BusinessContextFromServiceTest {

    private BusinessKnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        BusinessBotRegistry registry = new BusinessBotRegistry();
        registry.registerDefaults();
        knowledgeService = new BusinessKnowledgeService(registry);
    }

    @Test
    void resolve_usesProvidedBusinessContextInsteadOfRegistry() {
        BusinessChatReplyIo.BusinessContext context =
                new BusinessChatReplyIo.BusinessContext(
                        "Relojes Importados",
                        "ecommerce",
                        "Vendedor cercano",
                        List.of("No inventar precios"),
                        List.of(new BusinessChatReplyIo.FaqEntry("¿Envíos?", "A todo el país")),
                        List.of(
                                new BusinessChatReplyIo.CatalogEntry(
                                        "PRODUCT",
                                        "Submariner",
                                        "Réplica",
                                        "USD 180",
                                        "En stock",
                                        Map.of())),
                        List.of(
                                new BusinessChatReplyIo.RetrievedChunk(
                                        "doc-1",
                                        "chunk-1",
                                        "Garantía",
                                        "6 meses por fallas de fábrica.",
                                        0.91)));

        BusinessKnowledgeService.KnowledgeBase knowledge =
                knowledgeService.resolve("relojes-importados", context);

        Assertions.assertThat(knowledge.businessName()).isEqualTo("Relojes Importados");
        Assertions.assertThat(knowledge.industry()).isEqualTo("ecommerce");
        Assertions.assertThat(knowledge.text()).contains("Submariner");
        Assertions.assertThat(knowledge.text()).contains("USD 180");
        Assertions.assertThat(knowledge.text()).contains("Garantía");
        Assertions.assertThat(knowledge.text()).contains("6 meses");
        Assertions.assertThat(knowledge.text()).doesNotContain("Barbería Demo");
        Assertions.assertThat(knowledge.retrievedChunks()).hasSize(1);
    }
}
