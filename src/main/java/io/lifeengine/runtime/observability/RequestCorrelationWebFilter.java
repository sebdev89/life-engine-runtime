package io.lifeengine.runtime.observability;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Propaga {@code X-Request-Id} / {@code X-Correlation-Id} para los logs estructurados.
 *
 * <p>Este filtro escribe en el <b>Reactor Context y en ningún otro lado</b>. Que esos valores
 * lleguen al MDC —en el hilo que sea, incluidos los schedulers de una ejecución asíncrona— lo hace
 * {@link LogContext}, que registra un accessor por clave y deja que la propagación automática de
 * contexto los restaure alrededor de cada operador.
 *
 * <p>Antes había un segundo mecanismo acá mismo: un {@code doOnEach} que escribía el MDC en cada
 * señal y un {@code doFinally(MDC.clear())} que lo limpiaba. Se sacó. No era redundante, era
 * <b>dañino</b>: {@code MDC.clear()} borra el MDC entero, y el MDC contiene también el
 * {@code traceId} y el {@code spanId} que pone Micrometer. Una limpieza pensada para los campos de
 * este filtro se llevaba puesta la correlación de la traza, que es justo lo que hace falta para
 * reconstruir un incidente.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationWebFilter implements WebFilter {

    public static final String REQUEST_ID_KEY = LogContext.REQUEST_ID;
    public static final String CORRELATION_ID_KEY = LogContext.CORRELATION_ID;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = headerOrNew(exchange, "X-Request-Id");
        String correlationId = headerOrNew(exchange, "X-Correlation-Id");
        exchange.getResponse().getHeaders().add("X-Request-Id", requestId);
        exchange.getResponse().getHeaders().add("X-Correlation-Id", correlationId);

        String runId = extractRunId(exchange.getRequest().getPath().value());
        String workflowId = exchange.getRequest().getQueryParams().getFirst("workflowId");

        return chain.filter(exchange)
                .contextWrite(
                        ctx -> {
                            Context next =
                                    ctx.put(REQUEST_ID_KEY, requestId)
                                            .put(CORRELATION_ID_KEY, correlationId);
                            if (runId != null) {
                                next = next.put(LogContext.RUN_ID, runId);
                            }
                            if (workflowId != null) {
                                next = next.put(LogContext.WORKFLOW_ID, workflowId);
                            }
                            return next;
                        });
    }

    private static String extractRunId(String path) {
        String prefix = "/api/runtime/runs/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        String candidate = slash < 0 ? rest : rest.substring(0, slash);
        if (candidate.isBlank() || "stream".equals(candidate) || "events".equals(candidate)) {
            return null;
        }
        return candidate;
    }

    private static String headerOrNew(ServerWebExchange exchange, String name) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return UUID.randomUUID().toString();
    }
}
