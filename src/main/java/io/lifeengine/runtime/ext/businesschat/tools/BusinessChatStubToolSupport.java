package io.lifeengine.runtime.ext.businesschat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.tools.ToolExecutionResult;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import reactor.core.publisher.Mono;

/** Shared helpers for business-chat stub tools (no external integrations). */
final class BusinessChatStubToolSupport {

    private BusinessChatStubToolSupport() {}

    static Mono<ToolExecutionResult> executeStub(
            String toolId, String input, ObjectMapper mapper, WorkflowRunContext ctx, ObjectNode output) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }
        output.put("mode", "stub");
        String json = toJson(mapper, output);
        ctx.emit(EventType.TOOL_SUCCEEDED, java.util.Map.of("toolId", toolId, "stub", "true"), false);
        ctx.putToolOutput(toolId, json);
        return Mono.just(ToolExecutionResult.ok(toolId, json));
    }

    static ObjectNode parseInput(ObjectMapper mapper, String input) {
        try {
            JsonNode node = mapper.readTree(input == null || input.isBlank() ? "{}" : input);
            return node.isObject() ? (ObjectNode) node : mapper.createObjectNode();
        } catch (Exception ex) {
            return mapper.createObjectNode();
        }
    }

    private static String toJson(ObjectMapper mapper, ObjectNode output) {
        try {
            return mapper.writeValueAsString(output);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize tool output", ex);
        }
    }
}
