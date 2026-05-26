package io.lifeengine.runtime.observability;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/** Propagates {@code X-Request-Id} / {@code X-Correlation-Id} for structured logs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationWebFilter implements WebFilter {

    public static final String REQUEST_ID_KEY = "requestId";
    public static final String CORRELATION_ID_KEY = "correlationId";

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
                                next = next.put(RunLogContext.RUN_ID, runId);
                            }
                            if (workflowId != null) {
                                next = next.put(RunLogContext.WORKFLOW_ID, workflowId);
                            }
                            return next;
                        })
                .doOnEach(
                        signal -> {
                            if (signal.isOnNext() || signal.isOnComplete() || signal.isOnError()) {
                                MDC.put(REQUEST_ID_KEY, requestId);
                                MDC.put(CORRELATION_ID_KEY, correlationId);
                                if (runId != null) {
                                    MDC.put(RunLogContext.RUN_ID, runId);
                                }
                                if (workflowId != null) {
                                    MDC.put(RunLogContext.WORKFLOW_ID, workflowId);
                                }
                            }
                        })
                .doFinally(sig -> MDC.clear());
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
