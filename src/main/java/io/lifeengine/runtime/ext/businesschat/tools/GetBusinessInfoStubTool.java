package io.lifeengine.runtime.ext.businesschat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.tools.ToolDefinition;
import io.lifeengine.runtime.tools.ToolExecutionRequest;
import io.lifeengine.runtime.tools.ToolExecutionResult;
import io.lifeengine.runtime.tools.ToolExecutor;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Stub — resolves business profile fields without external calls. */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class GetBusinessInfoStubTool implements ToolExecutor {

    public static final String TOOL_ID = "business-chat.getBusinessInfo";

    private final ObjectMapper mapper;

    public GetBusinessInfoStubTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String toolId() {
        return TOOL_ID;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_ID, "Resolve business profile summary for the active bot (stub).");
    }

    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionRequest request, WorkflowRunContext ctx) {
        ObjectNode input = BusinessChatStubToolSupport.parseInput(mapper, request.input());
        ObjectNode output = mapper.createObjectNode();
        output.put("botId", text(input, "botId"));
        output.put("businessName", text(input, "businessName"));
        output.put("industry", text(input, "industry"));
        return BusinessChatStubToolSupport.executeStub(TOOL_ID, request.input(), mapper, ctx, output);
    }

    private static String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }
}
