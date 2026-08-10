package io.lifeengine.runtime.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** Prometheus metrics for runtime runs, stages, agents, tools, LLM, and SSE. */
@Component
public class RuntimeMetrics {

    private final MeterRegistry registry;

    public RuntimeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Una corrida que arrancó sin poder atribuirse a un tenant (W1-05, TD-TENANCY-001).
     *
     * <p>Hoy esto vale para el 100% del tráfico real: los tokens service-to-event no llevan el
     * claim {@code tenant} —lo agrega W2 con {@code act.tenant}— así que toda corrida disparada
     * por Business Chat, Dev Agent o ATP queda sin atribuir. Se cuenta en vez de rechazarse:
     * rechazar cortaría todo el tráfico, y disimularlo con un tenant por defecto escribiría un
     * dato falso que después nadie puede distinguir de uno real.
     *
     * <p>La métrica es el termómetro de W2: cuando {@code act.tenant} esté desplegado, esto tiene
     * que caer a cero. Mientras no lo haga, la brecha es visible.
     *
     * <p>Sin label de tenant, obviamente: lo que se cuenta es la ausencia. {@code workflowId} es
     * de cardinalidad acotada (5 workflows registrados) y ya se usa en el resto de las métricas.
     */
    public void recordMissingTenantClaim(String workflowId) {
        Counter.builder("runtime_tenancy_missing_claim_total")
                .description("Corridas iniciadas por un token sin claim de tenant — TD-TENANCY-001")
                .tag("workflowId", workflowId)
                .register(registry)
                .increment();
    }

    public void recordRunStarted(String workflowId) {
        Counter.builder("runtime.runs.started")
                .tag("workflowId", workflowId)
                .register(registry)
                .increment();
    }

    public void recordRunTerminal(String workflowId, String status) {
        Counter.builder("runtime.runs.terminal")
                .tag("workflowId", workflowId)
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public void recordStage(String stageType, String status) {
        Counter.builder("runtime.stages")
                .tag("stageType", stageType)
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public void recordAgent(String agentId, String status) {
        Counter.builder("runtime.agents")
                .tag("agentId", agentId)
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public void recordTool(String toolId, String status) {
        Counter.builder("runtime.tools")
                .tag("toolId", toolId)
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public void recordLlmCall(String model, String status) {
        Counter.builder("runtime.llm.calls")
                .tag("model", model)
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public void recordLlmFailure(String model) {
        Counter.builder("runtime.llm.failures").tag("model", model).register(registry).increment();
    }

    public void recordSseStreamOpened() {
        Counter.builder("runtime.sse.streams").register(registry).increment();
    }

    public Timer.Sample startRunTimer() {
        return Timer.start(registry);
    }

    public void stopRunTimer(Timer.Sample sample, String workflowId, String status) {
        sample.stop(
                Timer.builder("runtime.run.duration")
                        .tag("workflowId", workflowId)
                        .tag("status", status)
                        .register(registry));
    }
}
