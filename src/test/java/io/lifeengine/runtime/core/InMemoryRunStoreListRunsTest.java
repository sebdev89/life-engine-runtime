package io.lifeengine.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El doble en memoria tiene que listar igual que Postgres (KAN-252).
 *
 * <p>Si divergen, un test que pase acá y falle en producción es peor que no tener test: enseña a
 * desconfiar de la suite. Los casos de abajo son los mismos que {@code RunListingPersistenceTest}
 * corre contra Postgres real.
 */
@DisplayName("InMemoryRunStore.listRuns")
class InMemoryRunStoreListRunsTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    private final InMemoryRunStore store = new InMemoryRunStore();

    private Run run(String tenant, Instant createdAt) {
        UUID id = UUID.randomUUID();
        return new Run(
                id, RunStatus.SUCCEEDED, "demo.workflow", "corr-" + id, tenant, createdAt, createdAt,
                null, null, Map.of());
    }

    @Test
    @DisplayName("no devuelve corridas de otro tenant")
    void isolatesTenants() {
        Run mine = run("tenant-a", T0);
        store.saveRun(mine);
        store.saveRun(run("tenant-b", T0.plusSeconds(1)));

        List<Run> page = store.listRuns("tenant-a", 10, null, null);

        assertThat(page).extracting(Run::id).containsExactly(mine.id());
    }

    @Test
    @DisplayName("no devuelve corridas sin tenant — las S2S de TD-TENANCY-001")
    void excludesUnattributedRuns() {
        store.saveRun(run(null, T0));

        assertThat(store.listRuns("tenant-a", 10, null, null)).isEmpty();
    }

    @Test
    @DisplayName("las mas nuevas primero")
    void newestFirst() {
        Run older = run("tenant-a", T0);
        Run newer = run("tenant-a", T0.plusSeconds(60));
        store.saveRun(older);
        store.saveRun(newer);

        assertThat(store.listRuns("tenant-a", 10, null, null))
                .extracting(Run::id)
                .containsExactly(newer.id(), older.id());
    }

    @Test
    @DisplayName("el cursor no repite ni saltea, ni con created_at empatado")
    void keysetPagesWithoutGapsOnTies() {
        // Tres corridas con EXACTAMENTE el mismo instante: es el caso que rompe un cursor que
        // sólo lleva la fecha, y el que motiva el desempate por id.
        List<Run> all = List.of(run("tenant-a", T0), run("tenant-a", T0), run("tenant-a", T0));
        all.forEach(store::saveRun);

        List<Run> first = store.listRuns("tenant-a", 2, null, null);
        Run last = first.get(first.size() - 1);
        List<Run> second = store.listRuns("tenant-a", 2, last.createdAt(), last.id());

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(first).doesNotContainAnyElementsOf(second);
        assertThat(java.util.stream.Stream.concat(first.stream(), second.stream()).toList())
                .containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    @DisplayName("respeta el limite")
    void respectsLimit() {
        for (int i = 0; i < 5; i++) {
            store.saveRun(run("tenant-a", T0.plusSeconds(i)));
        }

        assertThat(store.listRuns("tenant-a", 3, null, null)).hasSize(3);
    }
}
