package io.lifeengine.runtime.api;

import io.lifeengine.runtime.domain.AgentStageRecord;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.llm.LlmCallRecord;
import java.util.List;

/** Internal aggregate before mapping to {@link RunDetailView}. */
public record RunDetailResponse(
        Run run, List<AgentStageRecord> agentStages, List<LlmCallRecord> llmCalls, List<RuntimeEvent> events) {

    public RunDetailView toView() {
        return RunDetailAssembler.assemble(run, agentStages, llmCalls, events);
    }
}
