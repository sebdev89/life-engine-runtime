package io.lifeengine.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.domain.AgentStageRecord;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.llm.LlmCallRecord;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * R2DBC repository-style integration test for {@link R2dbcRunStore}.
 *
 * <p>Runs against a real PostgreSQL (Testcontainers) and exercises the full read/write surface
 * of the {@link RunStore} contract — runs, runtime events, agent stage records, LLM call
 * records — to prove persistence survives across {@code RunStore} interactions (i.e. a run can
 * be hydrated after the writer-side store object is replaced, simulating a runtime restart).
 *
 * <p>Boots a minimal R2DBC stack (DatabaseClient + Flyway on JDBC) without loading the full
 * Spring application context so the test stays fast and independent of unrelated test fixtures
 * (security, LLM, workflow router). When Docker is unavailable the whole class is skipped via
 * a JUnit assumption so the rest of the unit suite stays green on machines without a daemon.
 */
class R2dbcRunStoreTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("life_engine_runtime_it")
                    .withUsername("life")
                    .withPassword("life");

    private static DatabaseClient databaseClient;
    private static R2dbcRunStore store;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeAll
    static void startContainerAndMigrate() {
        assumeTrue(
                dockerAvailable(),
                "Docker is required for R2dbcRunStoreTest — skipping the R2DBC integration suite.");
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        ConnectionFactory connectionFactory =
                io.r2dbc.spi.ConnectionFactories.get(
                        ConnectionFactoryOptions.builder()
                                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                                .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                                .option(
                                        ConnectionFactoryOptions.PORT,
                                        POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
                                .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                                .build());
        databaseClient = DatabaseClient.create(connectionFactory);
        store = new R2dbcRunStore(databaseClient, OBJECT_MAPPER);
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @BeforeEach
    void truncate() {
        assumeTrue(
                POSTGRES.isRunning(),
                "PostgreSQL container did not start (Docker unavailable) — skipping test.");
        databaseClient
                .sql("TRUNCATE TABLE runtime_event, runtime_agent_stage_record, runtime_llm_call_record, runtime_run RESTART IDENTITY CASCADE")
                .fetch()
                .rowsUpdated()
                .block();
    }

    @Test
    void saveRun_persistsAndHydratesAllFields() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID runId = UUID.randomUUID();
        Run run =
                new Run(
                        runId,
                        RunStatus.QUEUED,
                        "demo.llm.workflow",
                        "corr-" + runId,
                        now,
                        now,
                        null,
                        null,
                        Map.of("input", "hello", "tenant", "life-engine"));

        store.saveRun(run);

        Optional<Run> hydrated = store.findRun(runId);
        assertThat(hydrated).isPresent();
        assertThat(hydrated.get().id()).isEqualTo(runId);
        assertThat(hydrated.get().workflowId()).isEqualTo("demo.llm.workflow");
        assertThat(hydrated.get().status()).isEqualTo(RunStatus.QUEUED);
        assertThat(hydrated.get().metadata()).containsEntry("input", "hello").containsEntry("tenant", "life-engine");
    }

    @Test
    void saveRun_upsertsExistingRow_andTransitionsStatus() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID runId = UUID.randomUUID();
        Run queued =
                new Run(
                        runId, RunStatus.QUEUED, "demo.no-llm.workflow", "c1", now, now, null, null, Map.of());
        store.saveRun(queued);

        Run running = queued.withStatus(RunStatus.RUNNING, now.plusMillis(10)).withStartedAt(now.plusMillis(10));
        store.saveRun(running);

        Run succeeded = running.withStatus(RunStatus.SUCCEEDED, now.plusMillis(20));
        store.saveRun(succeeded);

        Run hydrated = store.findRun(runId).orElseThrow();
        assertThat(hydrated.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(hydrated.startedAt()).isEqualTo(now.plusMillis(10));
        assertThat(hydrated.finishedAt()).isEqualTo(now.plusMillis(20));
        // Single physical row — proves upsert (not insert-only).
        Long rowCount =
                databaseClient
                        .sql("SELECT COUNT(*) AS c FROM runtime_run WHERE id = :id")
                        .bind("id", runId)
                        .map(row -> row.get("c", Long.class))
                        .one()
                        .block();
        assertThat(rowCount).isEqualTo(1L);
    }

    @Test
    void appendEvent_storesAndReturnsInInsertionOrder() {
        Run run = seedRun();
        UUID runId = run.id();
        RuntimeEvent first =
                RuntimeEvent.of(runId, "RUN_STARTED", Map.of("workflowId", "demo.llm.workflow"), false);
        RuntimeEvent middle =
                RuntimeEvent.of(runId, "AGENT_STARTED", Map.of("agentId", "summarizer-agent"), false);
        RuntimeEvent terminal =
                RuntimeEvent.of(runId, "RUN_SUCCEEDED", Map.of("workflowId", "demo.llm.workflow"), true);

        store.appendEvent(first);
        store.appendEvent(middle);
        store.appendEvent(terminal);

        List<RuntimeEvent> events = store.eventsFor(runId);
        assertThat(events).hasSize(3);
        assertThat(events.get(0).type()).isEqualTo("RUN_STARTED");
        assertThat(events.get(1).type()).isEqualTo("AGENT_STARTED");
        assertThat(events.get(2).type()).isEqualTo("RUN_SUCCEEDED");
        assertThat(events.get(2).terminal()).isTrue();
        assertThat(events.get(1).attributes()).containsEntry("agentId", "summarizer-agent");
    }

    @Test
    void appendEvent_isIdempotentOnEventId() {
        Run run = seedRun();
        RuntimeEvent event = RuntimeEvent.of(run.id(), "RUN_STARTED", Map.of(), false);

        store.appendEvent(event);
        store.appendEvent(event);

        assertThat(store.eventsFor(run.id())).hasSize(1);
    }

    @Test
    void appendAgentStage_persistsAndOrders() {
        Run run = seedRun();
        UUID runId = run.id();
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AgentStageRecord stage1 =
                AgentStageRecord.agent(
                        "stage-1",
                        "summarizer-agent",
                        "SUCCEEDED",
                        t0,
                        t0.plusMillis(120),
                        "input-1",
                        "output-1",
                        null,
                        Map.of("order", "1"));
        AgentStageRecord stage2 =
                AgentStageRecord.tool(
                        "stage-2",
                        "demo.echo",
                        "FAILED",
                        t0.plusMillis(150),
                        t0.plusMillis(170),
                        "input-2",
                        null,
                        "boom",
                        Map.of("order", "2"));

        store.appendAgentStage(runId, stage1);
        store.appendAgentStage(runId, stage2);

        List<AgentStageRecord> hydrated = store.agentStagesFor(runId);
        assertThat(hydrated).hasSize(2);
        assertThat(hydrated.get(0).stageId()).isEqualTo("stage-1");
        assertThat(hydrated.get(0).stageType()).isEqualTo("AGENT");
        assertThat(hydrated.get(0).durationMs()).isEqualTo(120L);
        assertThat(hydrated.get(1).stageId()).isEqualTo("stage-2");
        assertThat(hydrated.get(1).status()).isEqualTo("FAILED");
        assertThat(hydrated.get(1).error()).isEqualTo("boom");
        assertThat(hydrated.get(1).metadata()).containsEntry("order", "2");
    }

    @Test
    void appendLlmCallRecord_persistsFullPayload() {
        Run run = seedRun();
        UUID runId = run.id();
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        LlmCallRecord record =
                new LlmCallRecord(
                        UUID.randomUUID(),
                        "stage-1",
                        "summarizer-agent",
                        "openai-compatible",
                        "Qwen/Qwen2.5-Coder-3B-Instruct",
                        "Summarize this incident.",
                        "{\"summary\": \"raw response\"}",
                        "{\"summary\": \"parsed\"}",
                        null,
                        t0,
                        t0.plusMillis(450),
                        450L,
                        Map.of("temperature", "0", "maxTokens", "256"));

        store.appendLlmCallRecord(runId, record);

        List<LlmCallRecord> hydrated = store.llmCallRecordsFor(runId);
        assertThat(hydrated).hasSize(1);
        LlmCallRecord first = hydrated.get(0);
        assertThat(first.id()).isEqualTo(record.id());
        assertThat(first.agentId()).isEqualTo("summarizer-agent");
        assertThat(first.model()).isEqualTo("Qwen/Qwen2.5-Coder-3B-Instruct");
        assertThat(first.prompt()).isEqualTo("Summarize this incident.");
        assertThat(first.rawResponse()).isEqualTo("{\"summary\": \"raw response\"}");
        assertThat(first.parsedResponse()).isEqualTo("{\"summary\": \"parsed\"}");
        assertThat(first.durationMs()).isEqualTo(450L);
        assertThat(first.metadata()).containsEntry("temperature", "0");
    }

    @Test
    void survivesAcrossStoreInstances_simulatingRuntimeRestart() {
        // Writer: persist a full run with events, stages, LLM calls.
        Run run = seedRun();
        UUID runId = run.id();
        store.appendEvent(RuntimeEvent.of(runId, "RUN_STARTED", Map.of(), false));
        store.appendAgentStage(
                runId,
                AgentStageRecord.agent(
                        "stage-1",
                        "summarizer-agent",
                        "SUCCEEDED",
                        Instant.now(),
                        Instant.now().plusMillis(10),
                        "in",
                        "out",
                        null,
                        Map.of()));
        store.appendLlmCallRecord(
                runId,
                new LlmCallRecord(
                        UUID.randomUUID(),
                        "stage-1",
                        "summarizer-agent",
                        "openai-compatible",
                        "test-model",
                        "p",
                        "r",
                        "r",
                        null,
                        Instant.now(),
                        Instant.now().plusMillis(20),
                        20L,
                        Map.of()));
        store.appendEvent(RuntimeEvent.of(runId, "RUN_SUCCEEDED", Map.of(), true));

        // Reader: a fresh R2dbcRunStore against the same DB (the runtime restarted).
        R2dbcRunStore freshStore = new R2dbcRunStore(databaseClient, OBJECT_MAPPER);

        assertThat(freshStore.findRun(runId)).isPresent();
        assertThat(freshStore.eventsFor(runId)).hasSize(2);
        assertThat(freshStore.agentStagesFor(runId)).hasSize(1);
        assertThat(freshStore.llmCallRecordsFor(runId)).hasSize(1);
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private Run seedRun() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID runId = UUID.randomUUID();
        Run run =
                new Run(
                        runId,
                        RunStatus.RUNNING,
                        "demo.llm.workflow",
                        "corr-" + runId,
                        now,
                        now,
                        now,
                        null,
                        Map.of("input", "seed"));
        store.saveRun(run);
        return run;
    }

}
