package io.lifeengine.runtime.ext.agenttesting.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Deterministic ATP evaluator stage — echoes structured evaluation payload for ATP service
 * consumption. Full rubric lives in ATP service for Lite; this stage validates workflow wiring.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.agent-testing.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class EvaluateTranscriptAgent implements AgentExecutor {

    public static final String AGENT_ID = "atp-evaluate-transcript-agent";

    private final ObjectMapper mapper;

    public EvaluateTranscriptAgent(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public Mono<AgentExecutionResult> execute(AgentExecutionRequest request, WorkflowRunContext ctx) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }
        ctx.emit(EventType.AGENT_STARTED, Map.of("agentId", AGENT_ID), false);
        try {
            JsonNode input = mapper.readTree(request.input() == null ? "{}" : request.input());
            ObjectNode output = mapper.createObjectNode();
            output.put("verdict", "PASS");
            output.put("score", 100);
            output.put("blockingCount", 0);
            output.set("scenarioId", input.path("scenarioId"));
            output.put("evaluator", "runtime-passthrough-v1");
            String json = mapper.writeValueAsString(output);
            ctx.emit(EventType.AGENT_SUCCEEDED, Map.of("agentId", AGENT_ID, "verdict", "PASS"), false);
            return Mono.just(AgentExecutionResult.ok(AGENT_ID, json));
        } catch (Exception ex) {
            ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", ex.getMessage()), false);
            return Mono.error(ex);
        }
    }
}
