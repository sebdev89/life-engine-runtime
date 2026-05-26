package io.lifeengine.runtime.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.domain.AgentStageRecord;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.llm.LlmCallRecord;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Parameters;
import io.r2dbc.spi.R2dbcType;
import io.r2dbc.spi.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL R2DBC implementation of {@link RunStore}.
 *
 * <p>Active by default — disable by setting {@code lifeengine.runtime.persistence.type=inmem}
 * (or activating the {@code test} profile), in which case {@link InMemoryRunStore} is wired
 * instead (see {@code application-test.yml} for the autoconfigure exclusions that keep tests
 * hermetic).
 *
 * <p>The {@link RunStore} contract is synchronous so this implementation blocks reactive
 * calls. Callers must invoke this store from a worker scheduler — every call site already
 * does (workflow executors run on {@code Schedulers.boundedElastic()} and {@code RunService}
 * / {@code RunEventStreamService} schedule their store hops on {@code boundedElastic}).
 */
@Component
@Primary
@ConditionalOnProperty(
        name = "lifeengine.runtime.persistence.type",
        havingValue = "r2dbc",
        matchIfMissing = true)
public class R2dbcRunStore implements RunStore {

    private static final Logger log = LoggerFactory.getLogger(R2dbcRunStore.class);
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(10);
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> ATTRIBUTES_TYPE = new TypeReference<>() {};

    private static final String UPSERT_RUN_SQL =
            """
            INSERT INTO runtime_run (
                id, status, workflow_id, correlation_id, created_at, updated_at,
                started_at, finished_at, metadata
            ) VALUES (
                :id, :status, :workflow_id, :correlation_id, :created_at, :updated_at,
                :started_at, :finished_at, :metadata
            )
            ON CONFLICT (id) DO UPDATE SET
                status         = EXCLUDED.status,
                workflow_id    = EXCLUDED.workflow_id,
                correlation_id = EXCLUDED.correlation_id,
                updated_at     = EXCLUDED.updated_at,
                started_at     = EXCLUDED.started_at,
                finished_at    = EXCLUDED.finished_at,
                metadata       = EXCLUDED.metadata
            """;

    private static final String FIND_RUN_SQL =
            """
            SELECT id, status, workflow_id, correlation_id, created_at, updated_at,
                   started_at, finished_at, metadata
            FROM runtime_run
            WHERE id = :id
            """;

    private static final String INSERT_EVENT_SQL =
            """
            INSERT INTO runtime_event (event_id, run_id, type, occurred_at, source, attributes, terminal)
            VALUES (:event_id, :run_id, :type, :occurred_at, :source, :attributes, :terminal)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String FIND_EVENTS_SQL =
            """
            SELECT event_id, run_id, type, occurred_at, source, attributes, terminal
            FROM runtime_event
            WHERE run_id = :run_id
            ORDER BY seq ASC
            """;

    private static final String INSERT_STAGE_SQL =
            """
            INSERT INTO runtime_agent_stage_record (
                run_id, stage_id, stage_type, name, status, started_at, finished_at,
                duration_ms, input, output, error, metadata
            ) VALUES (
                :run_id, :stage_id, :stage_type, :name, :status, :started_at, :finished_at,
                :duration_ms, :input, :output, :error, :metadata
            )
            """;

    private static final String FIND_STAGES_SQL =
            """
            SELECT stage_id, stage_type, name, status, started_at, finished_at,
                   duration_ms, input, output, error, metadata
            FROM runtime_agent_stage_record
            WHERE run_id = :run_id
            ORDER BY seq ASC
            """;

    private static final String INSERT_LLM_CALL_SQL =
            """
            INSERT INTO runtime_llm_call_record (
                id, run_id, stage_id, agent_id, provider, model, prompt, raw_response,
                parsed_response, parse_error, started_at, finished_at, duration_ms, metadata
            ) VALUES (
                :id, :run_id, :stage_id, :agent_id, :provider, :model, :prompt, :raw_response,
                :parsed_response, :parse_error, :started_at, :finished_at, :duration_ms, :metadata
            )
            ON CONFLICT (id) DO NOTHING
            """;

    private static final String FIND_LLM_CALLS_SQL =
            """
            SELECT id, stage_id, agent_id, provider, model, prompt, raw_response,
                   parsed_response, parse_error, started_at, finished_at, duration_ms, metadata
            FROM runtime_llm_call_record
            WHERE run_id = :run_id
            ORDER BY seq ASC
            """;

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    public R2dbcRunStore(DatabaseClient databaseClient, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveRun(Run run) {
        databaseClient
                .sql(UPSERT_RUN_SQL)
                .bind("id", run.id())
                .bind("status", run.status().name())
                .bind("workflow_id", run.workflowId())
                .bind("correlation_id", Parameters.in(R2dbcType.VARCHAR, run.correlationId()))
                .bind("created_at", run.createdAt())
                .bind("updated_at", run.updatedAt())
                .bind("started_at", Parameters.in(R2dbcType.TIMESTAMP_WITH_TIME_ZONE, run.startedAt()))
                .bind("finished_at", Parameters.in(R2dbcType.TIMESTAMP_WITH_TIME_ZONE, run.finishedAt()))
                .bind("metadata", jsonOf(run.metadata()))
                .fetch()
                .rowsUpdated()
                .then()
                .block(BLOCK_TIMEOUT);
    }

    @Override
    public Optional<Run> findRun(UUID runId) {
        Run run =
                databaseClient
                        .sql(FIND_RUN_SQL)
                        .bind("id", runId)
                        .map((row, meta) -> mapRun(row))
                        .one()
                        .blockOptional(BLOCK_TIMEOUT)
                        .orElse(null);
        return Optional.ofNullable(run);
    }

    @Override
    public void appendEvent(RuntimeEvent event) {
        databaseClient
                .sql(INSERT_EVENT_SQL)
                .bind("event_id", event.eventId())
                .bind("run_id", event.runId())
                .bind("type", event.type())
                .bind("occurred_at", event.occurredAt())
                .bind("source", event.source())
                .bind("attributes", jsonOf(event.attributes()))
                .bind("terminal", event.terminal())
                .fetch()
                .rowsUpdated()
                .then()
                .block(BLOCK_TIMEOUT);
    }

    @Override
    public List<RuntimeEvent> eventsFor(UUID runId) {
        return databaseClient
                .sql(FIND_EVENTS_SQL)
                .bind("run_id", runId)
                .map((row, meta) -> mapEvent(row))
                .all()
                .collectList()
                .blockOptional(BLOCK_TIMEOUT)
                .orElse(List.of());
    }

    @Override
    public void appendAgentStage(UUID runId, AgentStageRecord stage) {
        databaseClient
                .sql(INSERT_STAGE_SQL)
                .bind("run_id", runId)
                .bind("stage_id", stage.stageId())
                .bind("stage_type", stage.stageType())
                .bind("name", stage.name())
                .bind("status", stage.status())
                .bind("started_at", Parameters.in(R2dbcType.TIMESTAMP_WITH_TIME_ZONE, stage.startedAt()))
                .bind("finished_at", Parameters.in(R2dbcType.TIMESTAMP_WITH_TIME_ZONE, stage.finishedAt()))
                .bind("duration_ms", Parameters.in(R2dbcType.BIGINT, stage.durationMs()))
                .bind("input", Parameters.in(R2dbcType.CLOB, stage.input()))
                .bind("output", Parameters.in(R2dbcType.CLOB, stage.output()))
                .bind("error", Parameters.in(R2dbcType.CLOB, stage.error()))
                .bind("metadata", jsonOf(stage.metadata()))
                .fetch()
                .rowsUpdated()
                .then()
                .block(BLOCK_TIMEOUT);
    }

    @Override
    public List<AgentStageRecord> agentStagesFor(UUID runId) {
        return databaseClient
                .sql(FIND_STAGES_SQL)
                .bind("run_id", runId)
                .map((row, meta) -> mapStage(row))
                .all()
                .collectList()
                .blockOptional(BLOCK_TIMEOUT)
                .orElse(List.of());
    }

    @Override
    public void appendLlmCallRecord(UUID runId, LlmCallRecord record) {
        databaseClient
                .sql(INSERT_LLM_CALL_SQL)
                .bind("id", record.id())
                .bind("run_id", runId)
                .bind("stage_id", Parameters.in(R2dbcType.VARCHAR, record.stageId()))
                .bind("agent_id", Parameters.in(R2dbcType.VARCHAR, record.agentId()))
                .bind("provider", Parameters.in(R2dbcType.VARCHAR, record.provider()))
                .bind("model", Parameters.in(R2dbcType.VARCHAR, record.model()))
                .bind("prompt", Parameters.in(R2dbcType.CLOB, record.prompt()))
                .bind("raw_response", Parameters.in(R2dbcType.CLOB, record.rawResponse()))
                .bind("parsed_response", Parameters.in(R2dbcType.CLOB, record.parsedResponse()))
                .bind("parse_error", Parameters.in(R2dbcType.CLOB, record.parseError()))
                .bind("started_at", Parameters.in(R2dbcType.TIMESTAMP_WITH_TIME_ZONE, record.startedAt()))
                .bind("finished_at", Parameters.in(R2dbcType.TIMESTAMP_WITH_TIME_ZONE, record.finishedAt()))
                .bind("duration_ms", record.durationMs())
                .bind("metadata", jsonOf(record.metadata()))
                .fetch()
                .rowsUpdated()
                .then()
                .block(BLOCK_TIMEOUT);
    }

    @Override
    public List<LlmCallRecord> llmCallRecordsFor(UUID runId) {
        return databaseClient
                .sql(FIND_LLM_CALLS_SQL)
                .bind("run_id", runId)
                .map((row, meta) -> mapLlmCall(row))
                .all()
                .collectList()
                .blockOptional(BLOCK_TIMEOUT)
                .orElse(List.of());
    }

    // ---------------------------------------------------------------------------------------------
    // Mappers
    // ---------------------------------------------------------------------------------------------

    private Run mapRun(Row row) {
        return new Run(
                row.get("id", UUID.class),
                RunStatus.valueOf(row.get("status", String.class)),
                row.get("workflow_id", String.class),
                row.get("correlation_id", String.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class),
                row.get("started_at", Instant.class),
                row.get("finished_at", Instant.class),
                readJsonMap(row.get("metadata", Json.class), METADATA_TYPE));
    }

    private RuntimeEvent mapEvent(Row row) {
        return new RuntimeEvent(
                row.get("event_id", UUID.class),
                row.get("run_id", UUID.class),
                row.get("type", String.class),
                row.get("occurred_at", Instant.class),
                row.get("source", String.class),
                readJsonMap(row.get("attributes", Json.class), ATTRIBUTES_TYPE),
                Boolean.TRUE.equals(row.get("terminal", Boolean.class)));
    }

    private AgentStageRecord mapStage(Row row) {
        return new AgentStageRecord(
                row.get("stage_id", String.class),
                row.get("stage_type", String.class),
                row.get("name", String.class),
                row.get("status", String.class),
                row.get("started_at", Instant.class),
                row.get("finished_at", Instant.class),
                row.get("duration_ms", Long.class),
                row.get("input", String.class),
                row.get("output", String.class),
                row.get("error", String.class),
                readJsonMap(row.get("metadata", Json.class), ATTRIBUTES_TYPE));
    }

    private LlmCallRecord mapLlmCall(Row row) {
        Long duration = row.get("duration_ms", Long.class);
        return new LlmCallRecord(
                row.get("id", UUID.class),
                row.get("stage_id", String.class),
                row.get("agent_id", String.class),
                row.get("provider", String.class),
                row.get("model", String.class),
                row.get("prompt", String.class),
                row.get("raw_response", String.class),
                row.get("parsed_response", String.class),
                row.get("parse_error", String.class),
                row.get("started_at", Instant.class),
                row.get("finished_at", Instant.class),
                duration == null ? 0L : duration,
                readJsonMap(row.get("metadata", Json.class), ATTRIBUTES_TYPE));
    }

    // ---------------------------------------------------------------------------------------------
    // JSON helpers
    // ---------------------------------------------------------------------------------------------

    private Json jsonOf(Map<String, ?> values) {
        try {
            String serialized = objectMapper.writeValueAsString(values == null ? Map.of() : values);
            return Json.of(serialized);
        } catch (Exception e) {
            log.warn("Failed to serialize metadata; storing empty object. error={}", e.toString());
            return Json.of("{}");
        }
    }

    @SuppressWarnings("unchecked")
    private <V> Map<String, V> readJsonMap(Json json, TypeReference<Map<String, V>> typeRef) {
        if (json == null) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, V> parsed = objectMapper.readValue(json.asString(), typeRef);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            log.warn("Failed to deserialize JSON column; returning empty map. error={}", e.toString());
            return new LinkedHashMap<>();
        }
    }

}
