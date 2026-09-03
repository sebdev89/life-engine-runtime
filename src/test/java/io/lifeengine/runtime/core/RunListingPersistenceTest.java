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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * El SQL del listado, contra Postgres real (KAN-252).
 *
 * <p>Va contra Postgres y no contra el doble en memoria porque lo que se prueba es el SQL: la
 * comparación de tuplas {@code (created_at, id) < (:ca, :id)} del keyset y el orden compuesto. Un
 * error ahí compila, pasa cualquier test unitario, y recién se nota en producción como filas
 * repetidas entre páginas.
 *
 * <p>Cada test usa su propio tenant aleatorio: la tabla es compartida dentro del contenedor y un
 * tenant fijo haría que los tests se vieran entre sí.
 */
@DisplayName("listado de corridas — SQL de keyset (KAN-252)")
class RunListingPersistenceTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("life_engine_runtime_listing_it")
                    .withUsername("life")
                    .withPassword("life");

    private static R2dbcRunStore store;
    private static boolean started;

    @BeforeAll
    static void startContainerAndMigrate() {
        assumeTrue(dockerAvailable(), "Se necesita Docker para RunListingPersistenceTest — se saltea.");
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
        store =
                new R2dbcRunStore(
                        DatabaseClient.create(connectionFactory),
                        new ObjectMapper(),
                        TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)));
    }

    @AfterAll
    static void stopContainer() {
        if (started && POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static Run save(String tenant, Instant createdAt) {
        UUID id = UUID.randomUUID();
        Run run =
                new Run(
                        id, RunStatus.SUCCEEDED, "demo.listing.workflow", "corr-" + id, tenant,
                        createdAt, createdAt, null, null, Map.of());
        store.saveRun(run);
        return run;
    }

    @Test
    @DisplayName("scopea por tenant y ordena de la mas nueva a la mas vieja")
    void scopesByTenantAndOrdersNewestFirst() {
        String tenant = "t-" + UUID.randomUUID();
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Run older = save(tenant, base.minusSeconds(60));
        Run newer = save(tenant, base);
        save("t-" + UUID.randomUUID(), base);

        assertThat(store.listRuns(tenant, 10, null, null))
                .extracting(Run::id)
                .containsExactly(newer.id(), older.id());
    }

    @Test
    @DisplayName("una corrida sin tenant no aparece en ningun listado")
    void unattributedRunsAreInvisible() {
        String tenant = "t-" + UUID.randomUUID();
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
        save(null, base);
        Run mine = save(tenant, base);

        assertThat(store.listRuns(tenant, 10, null, null)).extracting(Run::id).containsExactly(mine.id());
    }

    @Test
    @DisplayName("el keyset no repite ni saltea filas con created_at empatado")
    void keysetHandlesTiesOnCreatedAt() {
        String tenant = "t-" + UUID.randomUUID();
        // Mismo instante exacto en las tres: sin el desempate por id, la comparación de tuplas
        // devolvería de nuevo la fila del borde y el cliente vería un duplicado.
        Instant sameInstant = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<UUID> allIds =
                List.of(
                        save(tenant, sameInstant).id(),
                        save(tenant, sameInstant).id(),
                        save(tenant, sameInstant).id());

        List<Run> first = store.listRuns(tenant, 2, null, null);
        Run boundary = first.get(first.size() - 1);
        List<Run> second = store.listRuns(tenant, 2, boundary.createdAt(), boundary.id());

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(first).doesNotContainAnyElementsOf(second);
        assertThat(java.util.stream.Stream.concat(first.stream(), second.stream()).map(Run::id).toList())
                .containsExactlyInAnyOrderElementsOf(allIds);
    }

    @Test
    @DisplayName("el cursor sobrevive la precision de microsegundos de TIMESTAMPTZ")
    void cursorSurvivesMicrosecondPrecision() {
        String tenant = "t-" + UUID.randomUUID();
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Run newer = save(tenant, base);
        Run older = save(tenant, base.minus(1, ChronoUnit.MICROS));

        List<Run> page = store.listRuns(tenant, 1, newer.createdAt(), newer.id());

        assertThat(page).extracting(Run::id).containsExactly(older.id());
    }
}
