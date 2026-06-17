package io.lifeengine.runtime.ext.businesschat.intelligence;

/**
 * Output of {@link BusinessKnowledgeNeedDetector}: the chosen retrieval strategy plus
 * the derived queries that each tool should execute.
 */
public record KnowledgeNeedDetection(
        KnowledgeNeedStrategy strategy,
        String reason,
        String ragQuery,
        String searchQuery) {

    public static KnowledgeNeedDetection none(String reason) {
        return new KnowledgeNeedDetection(KnowledgeNeedStrategy.NONE, reason, "", "");
    }

    public boolean needsRag() {
        return strategy == KnowledgeNeedStrategy.RAG_ONLY || strategy == KnowledgeNeedStrategy.HYBRID;
    }

    public boolean needsSearch() {
        return strategy == KnowledgeNeedStrategy.SEARCH_ONLY || strategy == KnowledgeNeedStrategy.HYBRID;
    }

    public boolean needsAny() {
        return strategy != KnowledgeNeedStrategy.NONE;
    }
}
