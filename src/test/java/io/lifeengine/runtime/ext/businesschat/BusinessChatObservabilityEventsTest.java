package io.lifeengine.runtime.ext.businesschat;

import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.businesschat.stages.BusinessContextAgent;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class BusinessChatObservabilityEventsTest {

    @Test
    void dedupKey_isStablePerTypeAndScope() {
        String key =
                BusinessChatObservabilityEvents.dedupKey(
                        EventType.INTENT_DETECTED, BusinessContextAgent.AGENT_ID);
        Assertions.assertThat(key).isEqualTo("INTENT_DETECTED:business-context-agent");
    }

    @Test
    void knowledgeLookupAttributes_includeCounts() {
        BusinessKnowledgeService.KnowledgeBase knowledge =
                new BusinessKnowledgeService.KnowledgeBase(
                        "demo",
                        "Demo Biz",
                        "retail",
                        "friendly",
                        List.of("rule"),
                        List.of(new BusinessBotDefinition.Faq("q", "a")),
                        List.of(Map.of("name", "item")),
                        List.of(Map.of("chunkId", "c1", "score", 0.9)),
                        "text");

        Assertions.assertThat(knowledge.retrievedChunks()).hasSize(1);
        Assertions.assertThat(knowledge.faqs()).hasSize(1);
        Assertions.assertThat(knowledge.catalogItems()).hasSize(1);
    }
}
