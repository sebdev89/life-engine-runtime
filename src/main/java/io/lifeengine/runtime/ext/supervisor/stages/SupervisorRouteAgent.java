package io.lifeengine.runtime.ext.supervisor.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.supervisor.SupervisorRouteCatalog;
import io.lifeengine.runtime.ext.supervisor.SupervisorRouteCatalog.RouteDecision;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Deterministic supervisor stage — selects target workflow from tenant/channel/intent. */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.supervisor.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SupervisorRouteAgent implements AgentExecutor {

    public static final String AGENT_ID = "supervisor-route-agent";

    private final ObjectMapper mapper;
    private final SupervisorRouteCatalog routeCatalog;

    public SupervisorRouteAgent(ObjectMapper mapper, SupervisorRouteCatalog routeCatalog) {
        this.mapper = mapper;
        this.routeCatalog = routeCatalog;
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
            String channel = textOrNull(input, "channel");
            String intent = textOrNull(input, "intent");
            String botId = textOrNull(input, "botId");

            RouteDecision decision = routeCatalog.resolve(tenantId, channel, intent);

            ObjectNode output = mapper.createObjectNode();
            output.put("workflowId", decision.workflowId());
            output.put("routeReason", decision.routeReason());
            output.put("policyVersion", decision.policyVersion());
            if (tenantId != null) {
                output.put("tenantId", tenantId);
            }
            if (channel != null) {
                output.put("channel", channel);
            }
            if (intent != null) {
                output.put("intent", intent);
            }
            if (botId != null) {
                output.put("botId", botId);
            }

            String json = mapper.writeValueAsString(output);
            ctx.emit(
                    EventType.ROUTING_DECISION,
                    Map.of(
                            "agentId",
                            AGENT_ID,
                            "workflowId",
                            decision.workflowId(),
                            "routeReason",
                            decision.routeReason()),
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
