package io.lifeengine.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * El tenant de una corrida se persiste, sobrevive al ciclo de vida, y no se pisa (W1-05).
 *
 * <p>Contra Postgres real: lo que se prueba es el SQL del upsert. El caso que motiva el
 * {@code COALESCE} es concreto y fácil de romper sin darse cuenta — una corrida se guarda tres
 * veces (QUEUED, RUNNING, terminal) y sólo la primera nace del request autenticado. Si el
 * {@code ON CONFLICT} pisara {@code tenant_id} con el valor entrante, el segundo save lo dejaría
 * en NULL y la corrida quedaría sin atribuir justo después de haberla atribuido bien.
 */
@DisplayName("tenant_id en runtime_run — W1-05")
class RunTenantPersistenceTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("life_engine_runtime_tenant_it")
                    .withUsername("life")
                    .withPassword("life");

    private static R2dbcRunStore store;
    private static boolean started;

    @BeforeAll
    static void startContainerAndMigrate() {
        assumeTrue(dockerAvailable(), "Se necesita Docker para RunTenantPersistenceTest — se saltea.");
        POSTGRES.start();
        started = true;

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        ConnectionFactory connectionFactory =
                ConnectionFactories.get(
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
        store = new R2dbcRunStore(DatabaseClient.create(connectionFactory), new ObjectMapper());
    }

    @AfterAll
    static void stopContainer() {
        if (started && POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    @DisplayName("se persiste y se recupera")
    void tenantIsPersistedAndHydrated() {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        store.saveRun(
                new Run(
                        runId,
                        RunStatus.QUEUED,
                        "demo.tenant.workflow",
                        "corr-" + runId,
                        "tenant-a",
                        now,
                        now,
                        null,
                        null,
                        Map.of()));

        assertThat(store.findRun(runId).orElseThrow().tenantId()).isEqualTo("tenant-a");
    }

    @Test
    @DisplayName("las transiciones de estado no borran el tenant")
    void tenantSurvivesTheRunLifecycle() {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        Run queued =
                new Run(
                        runId,
                        RunStatus.QUEUED,
                        "demo.tenant.workflow",
                        "corr-" + runId,
                        "tenant-a",
                        now,
                        now,
                        null,
                        null,
                        Map.of());
        store.saveRun(queued);
        store.saveRun(queued.withStatus(RunStatus.RUNNING, now.plusMillis(10)));
        store.saveRun(queued.withStatus(RunStatus.SUCCEEDED, now.plusMillis(20)));

        Run hydrated = store.findRun(runId).orElseThrow();
        assertThat(hydrated.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(hydrated.tenantId()).as("el tenant sobrevivió a las tres escrituras").isEqualTo("tenant-a");
    }

    /**
     * El caso que rompe el {@code COALESCE}: un save posterior que llega sin tenant no debe
     * borrar el que ya estaba. Sin la protección, esta aserción falla.
     */
    @Test
    @DisplayName("un save posterior sin tenant no borra el que ya estaba")
    void laterSaveWithoutTenantDoesNotEraseIt() {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        store.saveRun(
                new Run(
                        runId, RunStatus.QUEUED, "wf", "c", "tenant-a", now, now, null, null, Map.of()));

        store.saveRun(
                new Run(
                        runId, RunStatus.RUNNING, "wf", "c", null, now, now, now, null, Map.of()));

        assertThat(store.findRun(runId).orElseThrow().tenantId()).isEqualTo("tenant-a");
    }

    @Test
    @DisplayName("una corrida sin tenant se guarda igual — no se inventa un default")
    void runWithoutTenantIsStoredAsNull() {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        store.saveRun(
                new Run(runId, RunStatus.QUEUED, "wf", "c", null, now, now, null, null, Map.of()));

        assertThat(store.findRun(runId).orElseThrow().tenantId()).isNull();
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
