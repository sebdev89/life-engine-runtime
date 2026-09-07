package io.lifeengine.runtime.observability;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Las claves de contexto que aparecen en cada línea de log, y el mecanismo que las hace viajar.
 *
 * <p><b>El problema que resuelve.</b> En una app WebFlux el MDC de SLF4J es un {@code ThreadLocal},
 * y un pedido reactivo no tiene un hilo: salta entre el event-loop de Netty y los schedulers según
 * qué operador esté corriendo. Escribirle al MDC en un punto de la cadena no hace que el valor
 * exista en el siguiente.
 *
 * <p>Antes esto se compensaba a mano: {@code RequestCorrelationWebFilter} hacía {@code doOnEach} y
 * le escribía al MDC en cada señal, y limpiaba con {@code MDC.clear()} en {@code doFinally}. Eso
 * tenía dos defectos que se veían en producción:
 *
 * <ul>
 *   <li>sólo cubría las señales de <em>esa</em> cadena. Una ejecución asíncrona lanzada con
 *       {@code .subscribe()} arrancaba sin nada, y sus logs salían con los campos vacíos —
 *       justamente los logs que se leen cuando algo se rompe;
 *   <li>{@code MDC.clear()} borra el MDC <b>entero</b>, incluidos el {@code traceId} y el
 *       {@code spanId} que pone Micrometer. Una limpieza pensada para un pedido se llevaba puesta
 *       la correlación de la traza.
 * </ul>
 *
 * <p><b>Cómo se resuelve ahora.</b> Cada clave se registra como {@link ThreadLocalAccessor} en el
 * {@link ContextRegistry}. Con {@code spring.reactor.context-propagation=auto}, Reactor restaura el
 * MDC a partir del Context <em>alrededor de cada operador</em>, en el hilo que le toque, y lo deja
 * como estaba al salir. El único lugar donde se escribe es el Reactor Context; el MDC pasa a ser
 * una proyección de ese contexto y no una variable que alguien mantiene sincronizada.
 *
 * <p>Es el mismo mecanismo que Spring Boot usa para {@code traceId}/{@code spanId}, así que los
 * cinco campos del patrón de log viajan por una sola vía en vez de dos que hay que mantener de
 * acuerdo.
 */
@Configuration
public class LogContext {

    /** Identificador del pedido HTTP. Del header {@code X-Request-Id}, o generado. */
    public static final String REQUEST_ID = "requestId";

    /** Hilo de negocio que puede abarcar varios pedidos. Del header {@code X-Correlation-Id}. */
    public static final String CORRELATION_ID = "correlationId";

    /** Corrida de workflow. Se conoce recién cuando se crea, no al entrar el pedido. */
    public static final String RUN_ID = "runId";

    /** Workflow de esa corrida. */
    public static final String WORKFLOW_ID = "workflowId";

    /**
     * Tenant del llamador. <b>Se resuelve server-side</b>, del claim {@code tenant} del token — no
     * de un header. Un {@code X-Tenant-Id} que llega del cliente y se usa sin validar es un defecto
     * bloqueante, y eso vale también para lo que se escribe en un log: un tenant afirmado por el
     * cliente en el log es evidencia falsa dentro de una investigación.
     *
     * <p>Sale vacío para los tokens service-to-service, que hoy no afirman tenant
     * (TD-TENANCY-001). Vacío es la respuesta correcta ahí, no un hueco.
     */
    public static final String TENANT_ID = "tenantId";

    /** Las cinco, en el orden en que aparecen en el patrón de log. */
    public static final List<String> KEYS =
            List.of(REQUEST_ID, CORRELATION_ID, RUN_ID, WORKFLOW_ID, TENANT_ID);

    @PostConstruct
    void registerAccessors() {
        registerInto(ContextRegistry.getInstance());
    }

    /**
     * Registra un accessor por clave. Idempotente: {@code registerThreadLocalAccessor} reemplaza
     * por clave, así que llamarlo dos veces —el caso de un test que levanta más de un contexto—
     * no duplica nada.
     *
     * <p>Visible para los tests, que necesitan el registro sin levantar Spring entero.
     */
    public static void registerInto(ContextRegistry registry) {
        for (String key : KEYS) {
            registry.registerThreadLocalAccessor(new MdcKeyAccessor(key));
        }
    }

    /** Escribe en el Context sólo los valores presentes; un valor vacío no se propaga. */
    public static Context write(ContextView base, String key, String value) {
        return value == null || value.isBlank() ? Context.of(base) : Context.of(base).put(key, value);
    }

    /**
     * Un {@link ThreadLocalAccessor} sobre una clave del MDC.
     *
     * <p>{@code setValue()} sin argumentos es el camino de restauración: Reactor lo llama al salir
     * del operador. Tiene que <b>quitar la clave</b>, no dejarla en blanco — si dejara "" el hilo
     * quedaría con un valor vacío pegado, y el próximo trabajo que agarre ese hilo del pool
     * loggearía un requestId vacío en vez de ninguno. Es la diferencia entre "no había" y "había y
     * era nada", que en una investigación no es lo mismo.
     */
    private record MdcKeyAccessor(String mdcKey) implements ThreadLocalAccessor<String> {

        @Override
        public Object key() {
            return mdcKey;
        }

        @Override
        public String getValue() {
            return MDC.get(mdcKey);
        }

        @Override
        public void setValue(String value) {
            MDC.put(mdcKey, value);
        }

        @Override
        public void setValue() {
            MDC.remove(mdcKey);
        }
    }
}
