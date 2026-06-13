package io.lifeengine.runtime.ext.agenttesting.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Deterministic ATP evaluator — scores transcript against scenario assertions. */
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
            ObjectNode output = evaluate(input);
            String json = mapper.writeValueAsString(output);
            ctx.emit(
                    EventType.AGENT_SUCCEEDED,
                    Map.of("agentId", AGENT_ID, "verdict", output.path("verdict").asText()),
                    false);
            return Mono.just(AgentExecutionResult.ok(AGENT_ID, json));
        } catch (Exception ex) {
            ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", ex.getMessage()), false);
            return Mono.error(ex);
        }
    }

    ObjectNode evaluate(JsonNode input) {
        JsonNode assertions = input.path("assertions");
        JsonNode transcript = input.path("transcript");
        JsonNode operatorOutcome = input.path("operatorOutcome");

        ArrayNode assertionResults = mapper.createArrayNode();
        if (assertions.isArray()) {
            for (JsonNode spec : assertions) {
                assertionResults.add(evaluateAssertion(spec, transcript, operatorOutcome));
            }
        }

        int blockingFailures = 0;
        int passed = 0;
        for (JsonNode result : assertionResults) {
            if (result.path("passed").asBoolean(false)) {
                passed++;
            } else if (result.path("blocking").asBoolean(false)) {
                blockingFailures++;
            }
        }

        int total = assertionResults.size();
        int score = total == 0 ? 100 : (passed * 100) / total;
        String verdict =
                blockingFailures > 0
                        ? "FAIL"
                        : passed < total ? "WARN" : "PASS";

        ObjectNode output = mapper.createObjectNode();
        output.put("verdict", verdict);
        output.put("score", score);
        output.put("blockingCount", blockingFailures);
        output.set("assertionResults", assertionResults);
        output.set("scenarioId", input.path("scenarioId"));
        output.put("evaluator", "runtime-deterministic-v1");
        return output;
    }

    private ObjectNode evaluateAssertion(
            JsonNode spec, JsonNode transcript, JsonNode operatorOutcome) {
        String dimension = spec.path("dimension").asText("");
        ObjectNode result = mapper.createObjectNode();
        result.put("dimension", dimension);

        switch (dimension) {
            case "handoff_correctness" -> applyHandoffResult(result, spec, transcript);
            case "operator_reply" -> applyOperatorReplyResult(result, spec, operatorOutcome);
            case "lead_capture" -> applyLeadCaptureResult(result, spec, transcript);
            default -> {
                result.put("passed", false);
                result.put("blocking", true);
                result.put("message", "Unknown assertion dimension: " + dimension);
            }
        }
        return result;
    }

    private void applyHandoffResult(ObjectNode result, JsonNode spec, JsonNode transcript) {
        boolean expectedHandoff = spec.path("expectedHandoff").asBoolean(false);
        boolean observedHandoff = anyHandoffRequired(transcript);
        Integer maxTurn = spec.has("maxTurn") && !spec.path("maxTurn").isNull()
                ? spec.path("maxTurn").asInt()
                : null;

        if (expectedHandoff && maxTurn != null) {
            int handoffTurn = firstHandoffTurn(transcript);
            if (handoffTurn < 0) {
                result.put("passed", false);
                result.put("blocking", true);
                result.put("message", "Expected handoff by turn " + maxTurn + " but none observed");
                return;
            }
            if (handoffTurn > maxTurn) {
                result.put("passed", false);
                result.put("blocking", true);
                result.put(
                        "message",
                        "Handoff at turn " + handoffTurn + " exceeds max turn " + maxTurn);
                return;
            }
        }

        boolean passed = observedHandoff == expectedHandoff;
        result.put("passed", passed);
        result.put("blocking", true);
        result.put(
                "message",
                "Handoff observed="
                        + observedHandoff
                        + " matches expected="
                        + expectedHandoff);
    }

    private void applyOperatorReplyResult(ObjectNode result, JsonNode spec, JsonNode operatorOutcome) {
        boolean expected = spec.path("expectedOperatorReply").asBoolean(false);
        boolean observed =
                operatorOutcome.path("delivered").asBoolean(false)
                        && !operatorOutcome.path("content").asText("").isBlank();
        boolean passed = observed == expected;
        result.put("passed", passed);
        result.put("blocking", true);
        result.put(
                "message",
                "Operator reply observed=" + observed + " matches expected=" + expected);
    }

    private void applyLeadCaptureResult(ObjectNode result, JsonNode spec, JsonNode transcript) {
        List<String> requiredFields = readStringList(spec.path("requiredLeadFields"));
        JsonNode lead = lastLead(transcript);

        if (lead == null || lead.isEmpty()) {
            if (requiredFields.isEmpty()) {
                result.put("passed", true);
                result.put("blocking", false);
                result.put("message", "No lead required and none captured");
            } else {
                result.put("passed", false);
                result.put("blocking", true);
                result.put("message", "Expected lead fields but no lead captured");
            }
            return;
        }

        List<String> missing = new ArrayList<>();
        for (String field : requiredFields) {
            JsonNode value = lead.get(field);
            if (value == null || value.isNull() || value.asText("").isBlank()) {
                missing.add(field);
            }
        }

        if (missing.isEmpty()) {
            result.put("passed", true);
            result.put("blocking", true);
            result.put("message", "Lead contains required fields: " + requiredFields);
        } else {
            result.put("passed", false);
            result.put("blocking", true);
            result.put("message", "Lead missing required fields: " + missing);
        }
    }

    private static boolean anyHandoffRequired(JsonNode transcript) {
        if (!transcript.isArray()) {
            return false;
        }
        for (JsonNode turn : transcript) {
            if (turn.path("handoffRequired").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private static int firstHandoffTurn(JsonNode transcript) {
        if (!transcript.isArray()) {
            return -1;
        }
        for (JsonNode turn : transcript) {
            if (turn.path("handoffRequired").asBoolean(false)) {
                return turn.path("turn").asInt(-1);
            }
        }
        return -1;
    }

    private static JsonNode lastLead(JsonNode transcript) {
        if (!transcript.isArray()) {
            return null;
        }
        JsonNode last = null;
        for (JsonNode turn : transcript) {
            JsonNode lead = turn.path("lead");
            if (!lead.isMissingNode() && !lead.isNull() && !lead.isEmpty()) {
                last = lead;
            } else if (turn.path("leadCaptured").asBoolean(false)) {
                last = lead;
            }
        }
        return last;
    }

    private static List<String> readStringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Iterator<JsonNode> it = node.elements();
        while (it.hasNext()) {
            values.add(it.next().asText());
        }
        return List.copyOf(values);
    }
}
