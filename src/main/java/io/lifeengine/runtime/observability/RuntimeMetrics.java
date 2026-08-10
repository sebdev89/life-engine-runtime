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

    // ── Runtime V3 F1 — SPEC-009 §6ter ────────────────────────────────────────────────────
    // Ninguna lleva runId, tenant ni ids: `workflowId` está acotado por los workflows
    // registrados y `outcome` por sus tres valores. Con etiquetas de alta cardinalidad, estas
    // series harían explotar Prometheus en vez de explicar nada.

    /** Volumen del log. Detecta un run que emite de más. */
    public void recordEventAppended(String workflowId) {
        Counter.builder("runtime.event.appended")
                .tag("workflowId", workflowId == null || workflowId.isBlank() ? "unknown" : workflowId)
                .register(registry)
                .increment();
    }

    /**
     * Eventos que llegaron fuera de orden después del replay y se emitieron igual.
     *
     * <p>Debe ser 0 en régimen. Que no sea 0 no es un error del cliente: significa que dos eventos
     * se publicaron en orden distinto al que el log les asignó.
     */
    public void recordStreamReorderGap() {
        Counter.builder("runtime.stream.reorder.gap").register(registry).increment();
    }

    /** Reanudaciones por Last-Event-ID. {@code outcome} ∈ ok | invalid | ahead. */
    public void recordStreamResume(String outcome) {
        Counter.builder("runtime.stream.resume").tag("outcome", outcome).register(registry).increment();
    }

    /** Cuánto se está reteniendo mientras se ordena. Si crece, el orden está sufriendo. */
    public void registerReorderBufferGauge(java.util.function.Supplier<Number> size) {
        io.micrometer.core.instrument.Gauge.builder("runtime.stream.reorder.buffer.size", size)
                .register(registry);
    }

    /** Costo de proyectar el detalle de un run desde sus tablas derivadas. */
    public Timer.Sample startProjectionTimer() {
        return Timer.start(registry);
    }

    public void stopProjectionTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("runtime.projection.rebuild").register(registry));
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
