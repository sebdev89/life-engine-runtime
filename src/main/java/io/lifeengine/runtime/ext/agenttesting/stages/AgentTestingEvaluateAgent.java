package io.lifeengine.runtime.ext.agenttesting.stages;

import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Deterministic transcript evaluator for ATP ({@code agent-testing.evaluate.v1}). */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.agent-testing.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AgentTestingEvaluateAgent implements AgentExecutor {

    public static final String AGENT_ID = "agent-testing-evaluate-agent";

    private final AgentTestingDeterministicEvaluator evaluator;

    public AgentTestingEvaluateAgent(AgentTestingDeterministicEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public Set<String> capabilities() {
        return Set.of("execute", "evaluate", "deterministic");
    }

    @Override
    public Mono<AgentExecutionResult> execute(AgentExecutionRequest request, WorkflowRunContext ctx) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }
        ctx.emit(EventType.AGENT_STARTED, Map.of("agentId", AGENT_ID), false);
        try {
            String output = evaluator.evaluateJson(request.input());
            ctx.emit(
                    EventType.AGENT_SUCCEEDED,
                    Map.of("agentId", AGENT_ID, "structured", WorkflowRunContext.truncate(output, 500)),
                    false);
            return Mono.just(AgentExecutionResult.ok(AGENT_ID, output));
        } catch (RuntimeException ex) {
            String msg = AGENT_ID + ": " + ex.getMessage();
            ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
            return Mono.error(ex);
        }
    }
}
