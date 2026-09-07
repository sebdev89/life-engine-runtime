package io.lifeengine.runtime.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.context.ContextRegistry;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * El contexto de log tiene que sobrevivir un salto de hilo.
 *
 * <p>Es el defecto concreto que se midió en el cluster: los logs de la parte sincrónica de un
 * pedido salían completos y los de la ejecución asíncrona salían con todos los campos vacíos —
 * {@code [life-engine-runtime,,,,]}—, que son justo los renglones que se leen cuando algo se
 * rompe. En WebFlux el MDC es un {@code ThreadLocal} y un pedido reactivo no tiene un hilo: si el
 * contexto no viaja, el log miente por omisión.
 *
 * <p>Los tests de acá no usan Spring: ejercitan el mecanismo desnudo —accessors registrados más
 * propagación automática— sobre un {@code boundedElastic} real, que es el scheduler al que salta
 * la ejecución de un workflow.
 */
class LogContextCrossesThreadsTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeAll
    static void enablePropagation() {
        LogContext.registerInto(ContextRegistry.getInstance());
        // Es lo que activa `spring.reactor.context-propagation=auto` en la aplicación real.
        Hooks.enableAutomaticContextPropagation();
    }

    @AfterAll
    static void disablePropagation() {
        // Global al JVM: se apaga para no cambiarle el comportamiento a las clases que corran
        // después en el mismo fork de surefire.
        Hooks.disableAutomaticContextPropagation();
    }

    @BeforeEach
    void captureLogs() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LogContextCrossesThreadsTest.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.clear();
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    @Test
    @DisplayName("los campos del contexto llegan al MDC en OTRO hilo")
    void contextReachesMdcOnAnotherThread() {
        Mono<String> logueaEnOtroHilo =
                Mono.fromCallable(
                                () -> {
                                    logger.info("adentro del scheduler");
                                    return Thread.currentThread().getName();
                                })
                        .subscribeOn(Schedulers.boundedElastic());

        StepVerifier.create(
                        logueaEnOtroHilo.contextWrite(
                                ctx ->
                                        ctx.put(LogContext.REQUEST_ID, "req-1")
                                                .put(LogContext.TENANT_ID, "chizzini")
                                                .put(LogContext.RUN_ID, "run-1")))
                .assertNext(
                        hilo ->
                                assertThat(hilo)
                                        .as("el test no probaría nada si no hubiera salto de hilo")
                                        .startsWith("boundedElastic"))
                .verifyComplete();

        Map<String, String> mdc = appender.list.getFirst().getMDCPropertyMap();
        assertThat(mdc)
                .as("el contexto no cruzó al scheduler: es el log vacío que se ve en un incidente")
                .containsEntry(LogContext.REQUEST_ID, "req-1")
                .containsEntry(LogContext.TENANT_ID, "chizzini")
                .containsEntry(LogContext.RUN_ID, "run-1");
    }

    @Test
    @DisplayName("el hilo del pool queda limpio: un pedido no le presta su contexto al siguiente")
    void threadIsRestoredAfterwards() {
        // boundedElastic reusa hilos. Si la restauración dejara los valores puestos, el próximo
        // trabajo que agarre ese hilo loggearía el requestId de un pedido ajeno — un log que
        // atribuye actividad al pedido equivocado es peor que uno vacío.
        StepVerifier.create(
                        Mono.fromCallable(() -> MDC.get(LogContext.REQUEST_ID))
                                .subscribeOn(Schedulers.boundedElastic())
                                .contextWrite(ctx -> ctx.put(LogContext.REQUEST_ID, "req-primero")))
                .expectNext("req-primero")
                .verifyComplete();

        // Se envuelve en Optional porque Mono no admite un next nulo: sin esto, un MDC sucio y
        // un MDC limpio darían los dos "completa sin elementos" y el test pasaría con el bug.
        StepVerifier.create(
                        Mono.fromCallable(
                                        () ->
                                                java.util.Optional.ofNullable(
                                                        MDC.get(LogContext.REQUEST_ID)))
                                .subscribeOn(Schedulers.boundedElastic()))
                .assertNext(
                        heredado ->
                                assertThat(heredado)
                                        .as(
                                                "el segundo pedido no escribió requestId y no puede"
                                                    + " heredar el del primero: un log que le"
                                                    + " atribuye actividad al pedido equivocado es"
                                                    + " peor que uno vacío")
                                        .isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("clearRun() saca sus dos claves y NO se lleva puesto el traceId")
    void clearRunDoesNotWipeTracing() {
        // El bug que motivó esto vivía en el filtro: un MDC.clear() pensado para los campos del
        // pedido borraba también traceId y spanId, que los pone Micrometer. Sin traceId en la
        // línea no hay forma de saltar del log a la traza.
        MDC.put("traceId", "abc123");
        MDC.put("spanId", "def456");
        MDC.put(LogContext.RUN_ID, "run-1");
        MDC.put(LogContext.WORKFLOW_ID, "demo.llm.workflow");

        RunLogContext.clearRun();

        assertThat(MDC.get("traceId")).isEqualTo("abc123");
        assertThat(MDC.get("spanId")).isEqualTo("def456");
        assertThat(MDC.get(LogContext.RUN_ID)).isNull();
        assertThat(MDC.get(LogContext.WORKFLOW_ID)).isNull();
    }
}
