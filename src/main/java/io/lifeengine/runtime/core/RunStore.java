package io.lifeengine.runtime.core;

import io.lifeengine.runtime.domain.AgentStageRecord;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.llm.LlmCallRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Run persistence abstraction (in-memory today; Postgres later). */
public interface RunStore {

    void saveRun(Run run);

    Optional<Run> findRun(UUID runId);

    void appendEvent(RuntimeEvent event);

    List<RuntimeEvent> eventsFor(UUID runId);

    void appendAgentStage(UUID runId, AgentStageRecord stage);

    List<AgentStageRecord> agentStagesFor(UUID runId);

    void appendLlmCallRecord(UUID runId, LlmCallRecord record);

    List<LlmCallRecord> llmCallRecordsFor(UUID runId);
}
