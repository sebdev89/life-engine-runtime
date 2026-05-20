package io.lifeengine.runtime.core;

import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RuntimeEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

@Component
public class InMemoryRunStore {

    private final ConcurrentHashMap<UUID, Run> runs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<RuntimeEvent>> eventsByRun =
            new ConcurrentHashMap<>();

    public void saveRun(Run run) {
        runs.put(run.id(), run);
        eventsByRun.computeIfAbsent(run.id(), id -> new CopyOnWriteArrayList<>());
    }

    public Optional<Run> findRun(UUID runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    public void appendEvent(RuntimeEvent event) {
        eventsByRun
                .computeIfAbsent(event.runId(), id -> new CopyOnWriteArrayList<>())
                .add(event);
    }

    public List<RuntimeEvent> eventsFor(UUID runId) {
        CopyOnWriteArrayList<RuntimeEvent> list = eventsByRun.get(runId);
        if (list == null) {
            return List.of();
        }
        return List.copyOf(list);
    }

    public List<RuntimeEvent> eventsAfter(UUID runId, int fromIndexExclusive) {
        CopyOnWriteArrayList<RuntimeEvent> list = eventsByRun.get(runId);
        if (list == null || fromIndexExclusive >= list.size()) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(list.subList(fromIndexExclusive, list.size())));
    }
}
