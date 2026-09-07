package io.lifeengine.runtime.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.core.RunService;
import io.lifeengine.runtime.security.RuntimeTestJwt;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Una petición real tiene que dejar una línea de log con la que se pueda reconstruir el incidente.
 *
 * <p>Este test no mira propiedades ni configuración: hace un pedido autenticado de verdad, agarra
 * el evento que loguea {@link RunService} y lo pasa por el <b>patrón real de la aplicación</b>,
 * leído del {@code Environment}. Si alguien vuelve a pisar el patrón sin {@code traceId} —que es
 * exactamente lo que estaba pasando: {@code application.yml} declaraba un patrón propio y tapaba el
 * de Spring Boot, que sí lo trae— este test falla.
 *
 * <p>Lo que verifica de punta a punta:
 *
 * <ul>
 *   <li>que el patrón imprima los cinco campos, y no sólo que estén en el MDC;
 *   <li>que el {@code traceId} llegue al MDC en una app WebFlux, que sin
 *       {@code context-propagation=auto} no pasa;
 *   <li>que el {@code tenantId} salga del <b>token</b> y no de un header;
 *   <li>que todo eso sobreviva el salto a {@code boundedElastic} que hace {@code startRun}.
 * </ul>
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "lifeengine.runtime.security.enabled=true",
            "lifeengine.security.jwt.secret=" + RuntimeTestJwt.TEST_SECRET,
            // Sin tracing activo no hay traceId que buscar y el test pasaría sin probar nada.
            "management.tracing.enabled=true",
            "management.tracing.sampling.probability=1.0",
            // El exportador se apaga: acá interesa que el span exista y llegue al MDC, no
            // mandárselo a un colector que en un test no existe.
            "management.otlp.tracing.export.enabled=false"
        })
class LogLineCarriesTraceAndTenantTest {

    private static final String TENANT = "chizzini";

    @Autowired private WebTestClient webTestClient;
    @Autowired private Environment environment;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger runServiceLogger;

    @BeforeEach
    void captureLogs() {
        runServiceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RunService.class);
        appender = new ListAppender<>();
        appender.start();
        runServiceLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        runServiceLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("la línea de log de una corrida trae traceId, requestId y el tenant del token")
    void logLineIsCorrelatable() {
        webTestClient
                .post()
                .uri("/api/runtime/runs")
                .header(
                        "Authorization",
                        RuntimeTestJwt.bearerForTenant(TENANT, List.of("RUNTIME_OPERATOR")))
                .header("X-Request-Id", "req-kan301")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of(
                                "workflowId", "demo.no-llm.workflow",
                                "input", "una corrida para mirarle el log"))
                .exchange()
                .expectStatus()
                .isCreated();

        ILoggingEvent evento =
                appender.list.stream()
                        .filter(e -> e.getFormattedMessage().startsWith("Run started"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "RunService no logueó el arranque; sin esa línea el"
                                                    + " test no prueba nada"));

        Map<String, String> mdc = evento.getMDCPropertyMap();
        assertThat(mdc.get("traceId"))
                .as(
                        "sin traceId en el log no se puede saltar del log a la traza, que es la"
                            + " mitad de reconstruir un incidente")
                .isNotBlank();
        assertThat(mdc.get("spanId")).isNotBlank();
        assertThat(mdc.get(LogContext.REQUEST_ID)).isEqualTo("req-kan301");
        assertThat(mdc.get(LogContext.TENANT_ID))
                .as("el tenant sale del claim del token, resuelto server-side")
                .isEqualTo(TENANT);

        // Y ahora lo que de verdad se ve en un log: la línea renderizada con el patrón real.
        String patron = environment.getProperty("logging.pattern.level");
        assertThat(patron).as("la aplicación declara un patrón propio y este test lo audita").isNotBlank();

        String linea = render(patron, evento);
        assertThat(linea)
                .as("el patrón declara los campos pero no los imprime: %s", linea)
                .contains(mdc.get("traceId"))
                .contains("req-kan301")
                .contains(TENANT);
    }

    /** Renderiza un evento con el patrón real, resolviendo el {@code ${spring.application.name}}. */
    private String render(String patron, ILoggingEvent evento) {
        var contexto = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        PatternLayout layout = new PatternLayout();
        layout.setContext(contexto);
        layout.setPattern(
                patron.replace(
                        "${spring.application.name:}",
                        environment.getProperty("spring.application.name", "")));
        layout.start();
        try {
            return layout.doLayout(evento);
        } finally {
            layout.stop();
        }
    }
}
