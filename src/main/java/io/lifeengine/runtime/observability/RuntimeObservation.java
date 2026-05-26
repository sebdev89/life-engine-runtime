package io.lifeengine.runtime.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** OpenTelemetry spans (Micrometer Tracing) for workflow and stage execution. */
@Component
public class RuntimeObservation {

    private final Tracer tracer;

    public RuntimeObservation(Tracer tracer) {
        this.tracer = tracer;
    }

    public <T> Mono<T> observeRun(
            String workflowId, String runId, String correlationId, Mono<T> execution) {
        return trace(
                "runtime.run.execute",
                execution,
                "workflow.id",
                workflowId,
                "run.id",
                runId,
                "correlation.id",
                correlationId);
    }

    public <T> Mono<T> observeStage(
            String workflowId, String runId, String stageId, String stageType, Mono<T> execution) {
        return trace(
                "runtime.stage.execute",
                execution,
                "workflow.id",
                workflowId,
                "run.id",
                runId,
                "stage.id",
                stageId,
                "stage.type",
                stageType);
    }

    private <T> Mono<T> trace(String name, Mono<T> execution, String... tags) {
        return Mono.defer(
                () -> {
                    Span span = tracer.nextSpan().name(name).start();
                    for (int i = 0; i < tags.length; i += 2) {
                        span.tag(tags[i], tags[i + 1]);
                    }
                    return execution
                            .doOnError(span::error)
                            .doFinally(sig -> span.end());
                });
    }
}
