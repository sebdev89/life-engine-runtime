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
