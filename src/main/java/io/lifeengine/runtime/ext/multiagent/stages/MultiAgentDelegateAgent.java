package io.lifeengine.runtime.ext.multiagent.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.multiagent.SpecialistRegistry;
import io.lifeengine.runtime.ext.multiagent.SpecialistRegistry.SpecialistDefinition;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Delegates inbound work to a registered specialist workflow (A2A envelope scaffold). */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.multi-agent.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MultiAgentDelegateAgent implements AgentExecutor {

    public static final String AGENT_ID = "multi-agent-delegate-agent";

    private final ObjectMapper mapper;
    private final SpecialistRegistry specialistRegistry;

    public MultiAgentDelegateAgent(ObjectMapper mapper, SpecialistRegistry specialistRegistry) {
        this.mapper = mapper;
        this.specialistRegistry = specialistRegistry;
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
            String tenantId = textOrNull(input, "tenantId");
            String intent = textOrNull(input, "intent");
            SpecialistDefinition specialist = specialistRegistry.resolve(tenantId, intent);

            ObjectNode output = mapper.createObjectNode();
            output.put("specialistId", specialist.specialistId());
            output.put("workflowId", specialist.workflowId());
            output.put("delegationMode", "direct");
            output.put("policyVersion", "multi-agent-v1");
            if (tenantId != null) {
                output.put("tenantId", tenantId);
            }
            if (intent != null) {
                output.put("intent", intent);
            }

            String json = mapper.writeValueAsString(output);
            ctx.emit(
                    EventType.ROUTING_DECISION,
                    Map.of(
                            "agentId",
                            AGENT_ID,
                            "specialistId",
                            specialist.specialistId(),
                            "workflowId",
                            specialist.workflowId()),
                    false);
            ctx.emit(EventType.AGENT_SUCCEEDED, Map.of("agentId", AGENT_ID), false);
            return Mono.just(AgentExecutionResult.ok(AGENT_ID, json));
        } catch (Exception ex) {
            ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", ex.getMessage()), false);
            return Mono.error(ex);
        }
    }

    private static String textOrNull(JsonNode input, String field) {
        JsonNode node = input.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.isBlank() ? null : text;
    }
}
