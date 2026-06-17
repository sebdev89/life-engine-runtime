package io.lifeengine.runtime.ext.businesschat.intelligence;

/** Retrieval strategy decided by {@link BusinessKnowledgeNeedDetector} for each incoming message. */
public enum KnowledgeNeedStrategy {
    /** No retrieval needed: greetings, thanks, confirmations, handoff requests. */
    NONE,
    /** Query internal knowledge base only: pricing, services, policies, business rules. */
    RAG_ONLY,
    /** Query external web only: current news, live prices, legal changes, market data. */
    SEARCH_ONLY,
    /** Query both internal KB and external web: mixed internal + real-time signals. */
    HYBRID
}
