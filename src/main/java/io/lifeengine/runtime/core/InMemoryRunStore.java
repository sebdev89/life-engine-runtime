package io.lifeengine.runtime.core;

import io.lifeengine.runtime.domain.AgentStageRecord;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.llm.LlmCallRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

@Component
public class InMemoryRunStore implements RunStore {

    private final ConcurrentHashMap<UUID, Run> runs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<RuntimeEvent>> eventsByRun =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<AgentStageRecord>> agentStagesByRun =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<LlmCallRecord>> llmCallsByRun =
            new ConcurrentHashMap<>();

    @Override
    public void saveRun(Run run) {
        runs.put(run.id(), run);
        eventsByRun.computeIfAbsent(run.id(), id -> new CopyOnWriteArrayList<>());
    }

    @Override
    public Optional<Run> findRun(UUID runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public void appendEvent(RuntimeEvent event) {
        eventsByRun
                .computeIfAbsent(event.runId(), id -> new CopyOnWriteArrayList<>())
                .add(event);
    }

    @Override
    public List<RuntimeEvent> eventsFor(UUID runId) {
        CopyOnWriteArrayList<RuntimeEvent> list = eventsByRun.get(runId);
        if (list == null) {
            return List.of();
        }
        return List.copyOf(list);
    }

    @Override
    public void appendAgentStage(UUID runId, AgentStageRecord stage) {
        agentStagesByRun
                .computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>())
                .add(stage);
    }

    @Override
    public List<AgentStageRecord> agentStagesFor(UUID runId) {
        CopyOnWriteArrayList<AgentStageRecord> list = agentStagesByRun.get(runId);
        return list == null ? List.of() : List.copyOf(list);
    }

    @Override
    public void appendLlmCallRecord(UUID runId, LlmCallRecord record) {
        llmCallsByRun.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>()).add(record);
    }

    @Override
    public List<LlmCallRecord> llmCallRecordsFor(UUID runId) {
        CopyOnWriteArrayList<LlmCallRecord> list = llmCallsByRun.get(runId);
        return list == null ? List.of() : List.copyOf(list);
    }

    public List<RuntimeEvent> eventsAfter(UUID runId, int fromIndexExclusive) {
        CopyOnWriteArrayList<RuntimeEvent> list = eventsByRun.get(runId);
        if (list == null || fromIndexExclusive >= list.size()) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(list.subList(fromIndexExclusive, list.size())));
    }
}
