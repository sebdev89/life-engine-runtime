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

    WARNING_RECORDED;

    public String wireName() {
        return name();
    }
}
