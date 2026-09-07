package io.lifeengine.runtime.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.lifeengine.runtime.agents.LlmAgentSupport;
import io.lifeengine.runtime.api.RunResponse;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.core.RunService;
import io.lifeengine.runtime.workflow.DefinitionDrivenWorkflowExecutor;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Una corrida es UNA traza, no dos.
 *
 * <p>Medido contra el cluster antes de este arreglo: un solo {@code POST /api/runtime/runs} dejaba
 * en Jaeger dos trazas raíz distintas —el pedido HTTP por un lado, {@code runtime.run.execute} por
 * el otro— porque la ejecución se lanza con un {@code .subscribe()} que arranca con el Context
 * vacío. "Quién pidió esto" quedaba del otro lado de una frontera que ninguna consulta cruza.
 *
 * <p><b>Cómo se verifica sin un exportador de spans.</b> Dos logs de fases distintas comparten
 * {@code traceId} si y sólo si están en la misma traza. Es la propiedad que de verdad importa —es
 * la que se usa para reconstruir un incidente— y se afirma sobre el MDC de los eventos, sin sumar
 * una dependencia de test que después haya que mantener.
 *
 * <p><b>Por qué la corrida falla a propósito.</b> El LLM apunta a un puerto donde no hay nadie, así
 * que el workflow termina en error de conexión. Es deliberado por dos razones. La primera es que el
 * camino de error era <em>el peor</em>: el log de {@code Workflow failed} salía
 * {@code [life-engine-runtime,,,,]}, o sea que justo el renglón que se lee cuando algo se rompe no
 * tenía con qué correlacionarse. La segunda es que un {@code MockWebServer} acá sería frágil sin
 * necesidad: Spring cachea el contexto entre clases de test y las propiedades dinámicas no forman
 * parte de la clave de caché, así que la URL del mock no siempre gana — se comprobó, el cliente
 * terminaba pegándole al default de {@code application-test.yml}. Un test que a veces prueba otra
 * cosa es peor que uno que prueba menos.
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            // Sin tracing activo no hay traceId que comparar y el test pasaría sin probar nada.
            "management.tracing.enabled=true",
            "management.tracing.sampling.probability=1.0",
            "management.otlp.tracing.export.enabled=false",
            // Puerto sin nadie escuchando: la llamada al modelo falla por conexión, sin esperas.
            "runtime.llm.base-url=http://localhost:1",
            "runtime.llm.timeout=2s"
        })
class RunKeepsOneTraceTest {

    private static final List<Class<?>> ORIGENES =
            List.of(RunService.class, DefinitionDrivenWorkflowExecutor.class, LlmAgentSupport.class);

    @Autowired private WebTestClient webTestClient;

    private final Map<Class<?>, ListAppender<ILoggingEvent>> appenders = new LinkedHashMap<>();

    @BeforeEach
    void captureLogs() {
        for (Class<?> origen : ORIGENES) {
            var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(origen);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            appenders.put(origen, appender);
        }
    }

    @AfterEach
    void releaseLogs() {
        appenders.forEach(
                (origen, appender) -> {
                    ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(origen))
                            .detachAppender(appender);
                    appender.stop();
                });
        appenders.clear();
    }

    @Test
    @DisplayName("el pedido, la ejecución asíncrona, la llamada al LLM y el fallo comparten traceId")
    void everyPhaseOfARunSharesOneTrace() {
        UUID runId = startRun();
        awaitTerminal(runId);

        // Fase 1 — el pedido HTTP. Sincrónica; esta parte ya funcionaba.
        String trazaDelPedido = traceId(RunService.class, m -> m.startsWith("Run started"));
        assertThat(trazaDelPedido)
                .as("sin traceId en el log del pedido este test no compara nada")
                .isNotBlank();

        // Fase 2 — la ejecución asíncrona. Es la frontera que rompía la traza.
        String trazaDeLaEtapa =
                traceId(DefinitionDrivenWorkflowExecutor.class, m -> m.startsWith("Executing agent"));
        assertThat(trazaDeLaEtapa)
                .as("la ejecución abría una traza raíz nueva: es el bug de KAN-302")
                .isEqualTo(trazaDelPedido);

        // Fase 3 — la llamada saliente al modelo, que vive fuera del proceso.
        String trazaDelLlm = traceId(LlmAgentSupport.class, m -> m.startsWith("Calling LLM"));
        assertThat(trazaDelLlm)
                .as("la llamada más cara y más frágil de la corrida quedaba fuera de la traza")
                .isEqualTo(trazaDelPedido);

        // Fase 4 — el fallo. El renglón que se lee cuando algo se rompe.
        String trazaDelFallo =
                traceId(DefinitionDrivenWorkflowExecutor.class, m -> m.startsWith("Workflow failed"));
        assertThat(trazaDelFallo)
                .as("el log de un workflow fallido salía sin nada con qué correlacionarlo")
                .isEqualTo(trazaDelPedido);
    }

    @Test
    @DisplayName("la parte asíncrona también arrastra runId, workflowId y correlationId")
    void asyncPhaseKeepsTheRunIdentity() {
        UUID runId = startRun();
        awaitTerminal(runId);

        Map<String, String> mdc =
                evento(DefinitionDrivenWorkflowExecutor.class, m -> m.startsWith("Workflow failed"))
                        .getMDCPropertyMap();

        assertThat(mdc)
                .as("sin esto hay que leer el mensaje a mano para saber de qué corrida habla")
                .containsEntry(LogContext.RUN_ID, runId.toString())
                .containsEntry(LogContext.WORKFLOW_ID, "demo.llm.workflow");
        assertThat(mdc.get(LogContext.CORRELATION_ID)).isNotBlank();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private ILoggingEvent evento(Class<?> origen, Predicate<String> mensaje) {
        return appenders.get(origen).list.stream()
                .filter(e -> mensaje.test(e.getFormattedMessage()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "no apareció la línea esperada en "
                                                + origen.getSimpleName()
                                                + "; sin ella el test no prueba nada"));
    }

    private String traceId(Class<?> origen, Predicate<String> mensaje) {
        return evento(origen, mensaje).getMDCPropertyMap().get("traceId");
    }

    private UUID startRun() {
        RunResponse run =
                webTestClient
                        .post()
                        .uri("/api/runtime/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(
                                Map.of(
                                        "workflowId", "demo.llm.workflow",
                                        "input",
                                                "[INCIDENT] saturacion de CPU en node-3."
                                                    + " [ACTION REQUIRED] revisar el escalado."))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(RunResponse.class)
                        .returnResult()
                        .getResponseBody();
        assertThat(run).isNotNull();
        return run.runId();
    }

    private void awaitTerminal(UUID runId) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(
                        () -> {
                            RunResponse run =
                                    webTestClient
                                            .get()
                                            .uri("/api/runtime/runs/{id}", runId)
                                            .exchange()
                                            .expectStatus()
                                            .isOk()
                                            .expectBody(RunResponse.class)
                                            .returnResult()
                                            .getResponseBody();
                            assertThat(run).isNotNull();
                            assertThat(run.status().isTerminal()).isTrue();
                        });
    }
}
