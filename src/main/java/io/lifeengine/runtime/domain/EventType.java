package io.lifeengine.runtime.domain;

/** Canonical runtime event types (SSE + run detail replay). */
public enum EventType {
    RUN_STARTED,
    RUN_SUCCEEDED,
    RUN_FAILED,
    RUN_CANCELLED,

    STAGE_STARTED,
    STAGE_SUCCEEDED,
    STAGE_FAILED,
    STAGE_CANCELLED,

    AGENT_STARTED,
    AGENT_SUCCEEDED,
    AGENT_FAILED,

    TOOL_STARTED,
    TOOL_SUCCEEDED,
    TOOL_FAILED,

    LLM_CALL_STARTED,
    LLM_CALL_SUCCEEDED,
    LLM_CALL_FAILED,

    /** @deprecated Prefer {@link #CONVERSATION_STARTED}. */
    @Deprecated
    BUSINESS_CHAT_STARTED,
    /** @deprecated Prefer {@link #RESPONSE_GENERATED}. */
    @Deprecated
    BUSINESS_CHAT_RESPONDED,
    /** @deprecated Prefer {@link #HANDOFF_DECISION}. */
    @Deprecated
    BUSINESS_CHAT_HANDOFF,

    CONVERSATION_STARTED,
    INTENT_DETECTED,
    KNOWLEDGE_LOOKUP,
    TOOL_REQUESTED,
    TOOL_COMPLETED,
    LEAD_CAPTURED,
    LEAD_SCORE_UPDATED,
    CONFIDENCE_UPDATED,
    HANDOFF_DECISION,
    ROUTING_DECISION,
    RESPONSE_GENERATED,
    CONVERSATION_COMPLETED,
    CONVERSATION_QUALITY_EVALUATED,

    WARNING_RECORDED;

    public String wireName() {
        return name();
    }
}
