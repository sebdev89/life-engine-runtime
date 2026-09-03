package io.lifeengine.runtime.core;

import io.lifeengine.runtime.domain.AgentStageRecord;
import io.lifeengine.runtime.domain.EventSequence;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.llm.LlmCallRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
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

    /**
     * Equivalente en memoria de la secuencia de Postgres: global, no por run. Sólo se le pide
     * monotonía dentro de cada run, que es lo único que el orden necesita.
     */
    private final AtomicLong seqGenerator = new AtomicLong();

    @Override
    public void saveRun(Run run) {
        runs.put(run.id(), run);
        eventsByRun.computeIfAbsent(run.id(), id -> new CopyOnWriteArrayList<>());
    }

    @Override
    public Optional<Run> findRun(UUID runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    /**
     * Mismo orden que el store R2DBC —{@code (created_at DESC, id DESC)}— para que un test no vea
     * un orden distinto según la persistencia que le toque.
     */
    private static final Comparator<Run> NEWEST_FIRST =
            Comparator.comparing(Run::createdAt).thenComparing(Run::id).reversed();

    @Override
    public List<Run> listRuns(String tenantId, int limit, Instant createdBefore, UUID beforeId) {
        return runs.values().stream()
                .filter(run -> tenantId.equals(run.tenantId()))
                .filter(run -> isBeforeCursor(run, createdBefore, beforeId))
                .sorted(NEWEST_FIRST)
                .limit(limit)
                .toList();
    }

    private static boolean isBeforeCursor(Run run, Instant createdBefore, UUID beforeId) {
        if (createdBefore == null || beforeId == null) {
            return true;
        }
        int byTime = run.createdAt().compareTo(createdBefore);
        return byTime < 0 || (byTime == 0 && run.id().compareTo(beforeId) < 0);
    }

    @Override
    public RuntimeEvent appendEvent(RuntimeEvent event) {
        CopyOnWriteArrayList<RuntimeEvent> list =
                eventsByRun.computeIfAbsent(event.runId(), id -> new CopyOnWriteArrayList<>());
        // Idempotencia por eventId, igual que el store R2DBC: reintentar no duplica y devuelve el
        // seq original. Sin esto, los tests que comparten backend verían comportamientos distintos
        // según la persistencia, que es justo lo que un doble de test no debe hacer.
        for (RuntimeEvent existing : list) {
            if (existing.eventId().equals(event.eventId())) {
                return existing;
            }
        }
        RuntimeEvent stored = event.withSeq(EventSequence.of(seqGenerator.incrementAndGet()));
        list.add(stored);
        return stored;
    }

    @Override
    public RuntimeEvent appendEventAndSaveRun(RuntimeEvent event, Run run) {
        // No hay transacción real en memoria. El orden sí se respeta —evento primero— y con eso
        // alcanza para que los tests que verifican la invariante corran contra este backend.
        RuntimeEvent stored = appendEvent(event);
        saveRun(run);
        return stored;
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
