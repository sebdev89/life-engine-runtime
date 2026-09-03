package io.lifeengine.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.runtime.api.RunPageCursor.InvalidCursorException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RunPageCursor")
class RunPageCursorTest {

    @Test
    @DisplayName("ida y vuelta sin perder precision del instante")
    void roundTripsKeepingNanos() {
        // Postgres guarda TIMESTAMPTZ con microsegundos. Si el cursor redondeara a milisegundos,
        // la comparación de tuplas devolvería de nuevo la última fila de la página anterior.
        Instant withMicros = Instant.parse("2026-09-01T10:00:00.123456Z");
        RunPageCursor original = new RunPageCursor(withMicros, UUID.randomUUID());

        RunPageCursor decoded = RunPageCursor.decode(original.encode());

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("es opaco: no filtra la fecha en claro")
    void isOpaque() {
        String encoded = new RunPageCursor(Instant.parse("2026-09-01T10:00:00Z"), UUID.randomUUID()).encode();

        assertThat(encoded).doesNotContain("2026").doesNotContain(":");
    }

    @Test
    @DisplayName("un cursor que este servicio no emitio es un error del llamador")
    void rejectsGarbage() {
        assertThatThrownBy(() -> RunPageCursor.decode("no-es-un-cursor"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("no hay cursor siguiente si la pagina vino incompleta")
    void noNextCursorOnPartialPage() {
        List<RunSummaryView> partial =
                List.of(
                        new RunSummaryView(
                                UUID.randomUUID(), "SUCCEEDED", "w", "c",
                                Instant.parse("2026-09-01T10:00:00Z"),
                                Instant.parse("2026-09-01T10:00:00Z"), null, null));

        assertThat(RunPageCursor.nextAfter(partial, 20)).isNull();
        assertThat(RunPageCursor.nextAfter(partial, 1)).isNotNull();
    }
}
