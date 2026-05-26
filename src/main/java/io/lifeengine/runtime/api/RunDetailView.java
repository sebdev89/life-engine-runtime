package io.lifeengine.runtime.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

/** Canonical replay/rehydration response for GET /runs/{runId}. */
public record RunDetailView(
        @JsonUnwrapped RunResponse run,
        List<AgentStageView> agentStages,
        List<LlmCallView> llmCalls,
        List<RuntimeEventResponse> events,
        String terminalError) {}
