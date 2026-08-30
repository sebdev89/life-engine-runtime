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

    /**
     * Persiste el evento y lo devuelve con el {@code seq} que le asignó el log.
     *
     * <p>Devuelve el evento en vez de {@code void} porque quien publica en vivo necesita el mismo
     * número que quedó guardado: si publicara el objeto de entrada, el cliente vería por SSE un
     * evento sin lugar en el orden y no podría reanudar. Ver ADR-RT-012.
     *
     * <p>Idempotente por {@code eventId}: reintentar con el mismo id no duplica y devuelve el
     * {@code seq} original.
     */
    RuntimeEvent appendEvent(RuntimeEvent event);

    /**
     * Escribe el evento y la proyección del run en una sola transacción, <b>el evento primero</b>.
     *
     * <p>Es la invariante de ADR-RT-003: ninguna transición de estado se guarda sin que el evento
     * que la justifica esté escrito. Si la escritura de la proyección falla, la transacción se
     * deshace entera y no queda un estado sin su evento.
     */
    RuntimeEvent appendEventAndSaveRun(RuntimeEvent event, Run run);

    List<RuntimeEvent> eventsFor(UUID runId);

    void appendAgentStage(UUID runId, AgentStageRecord stage);

    List<AgentStageRecord> agentStagesFor(UUID runId);

    void appendLlmCallRecord(UUID runId, LlmCallRecord record);

    List<LlmCallRecord> llmCallRecordsFor(UUID runId);
}
