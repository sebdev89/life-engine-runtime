package io.lifeengine.runtime.ext.agenttesting.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Deterministic ATP-style evaluator executed inside Runtime for
 * {@code agent-testing.evaluate.v1}. Mirrors ATP {@code DeterministicEvaluator}
 * without ATP domain types.
 */
@Component
public class AgentTestingDeterministicEvaluator {

    private final ObjectMapper objectMapper;

    public AgentTestingDeterministicEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String evaluateJson(String inputJson) {
        try {
            JsonNode root = objectMapper.readTree(inputJson);
            List<JsonNode> transcript = readArray(root.path("transcript"));
            List<JsonNode> assertions = readArray(root.path("assertions"));
            JsonNode operatorOutcome = root.path("operatorOutcome");

            List<AssertionResult> results = new ArrayList<>();
            for (JsonNode spec : assertions) {
                results.add(evaluateAssertion(spec, transcript, operatorOutcome));
            }
            int blockingFailures =
                    (int) results.stream().filter(r -> !r.passed && r.blocking).count();
            int passed = (int) results.stream().filter(r -> r.passed).count();
            int score = results.isEmpty() ? 100 : (passed * 100) / results.size();
            String verdict =
                    blockingFailures > 0
                            ? "FAIL"
                            : results.stream().anyMatch(r -> !r.passed) ? "WARN" : "PASS";

            var output = objectMapper.createObjectNode();
            output.put("verdict", verdict);
            output.put("score", score);
            output.put("blockingCount", blockingFailures);
            var resultsNode = output.putArray("assertionResults");
            for (AssertionResult result : results) {
                var node = resultsNode.addObject();
                node.put("dimension", result.dimension);
                node.put("passed", result.passed);
                node.put("blocking", result.blocking);
                node.put("message", result.message);
            }
            return objectMapper.writeValueAsString(output);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "agent_testing_evaluate_input_invalid: " + ex.getMessage(), ex);
        }
    }

    private AssertionResult evaluateAssertion(
            JsonNode spec, List<JsonNode> transcript, JsonNode operatorOutcome) {
        String dimension = spec.path("dimension").asText("");
        return switch (dimension) {
            case "handoff_correctness" -> evaluateHandoff(spec, transcript);
            case "handoff_timing" -> evaluateHandoffTiming(spec, transcript);
            case "operator_reply" -> evaluateOperatorReply(spec, operatorOutcome);
            case "lead_capture" -> evaluateLeadCapture(spec, transcript);
            case "quality" -> evaluateQuality(spec, transcript);
            case "conversational_quality" -> evaluateConversationalQuality(spec, transcript);
            case "context_retention" -> evaluateRetention(spec, transcript, "context_retention");
            case "product_retention" -> evaluateRetention(spec, transcript, "product_retention");
            case "long_context_retention" ->
                    evaluateRetention(spec, transcript, "long_context_retention");
            case "no_false_out_of_domain" ->
                    evaluateNoFalseIntent(spec, transcript, "out_of_domain", "no_false_out_of_domain");
            case "no_false_unclear" ->
                    evaluateNoFalseIntent(spec, transcript, "unclear", "no_false_unclear");
            default ->
                    new AssertionResult(
                            dimension, false, true, "Unknown assertion dimension: " + dimension);
        };
    }

    private AssertionResult evaluateHandoff(JsonNode spec, List<JsonNode> transcript) {
        boolean expectedHandoff = spec.path("expectedHandoff").asBoolean(false);
        boolean observedHandoff =
                transcript.stream().anyMatch(turn -> turn.path("handoffRequired").asBoolean(false));
        Integer maxTurn = intOrNull(spec, "maxTurn");
        if (expectedHandoff && maxTurn != null) {
            int handoffTurn =
                    transcript.stream()
                            .filter(turn -> turn.path("handoffRequired").asBoolean(false))
                            .mapToInt(turn -> turn.path("turn").asInt(-1))
                            .findFirst()
                            .orElse(-1);
            if (handoffTurn < 0) {
                return fail(
                        "handoff_correctness",
                        true,
                        "Expected handoff by turn " + maxTurn + " but none observed");
            }
            if (handoffTurn > maxTurn) {
                return fail(
                        "handoff_correctness",
                        true,
                        "Handoff at turn " + handoffTurn + " exceeds max turn " + maxTurn);
            }
        }
        if (observedHandoff == expectedHandoff) {
            return pass(
                    "handoff_correctness",
                    true,
                    "Handoff observed=" + observedHandoff + " matches expected=" + expectedHandoff);
        }
        return fail(
                "handoff_correctness",
                true,
                "Handoff observed=" + observedHandoff + " expected=" + expectedHandoff);
    }

    private AssertionResult evaluateHandoffTiming(JsonNode spec, List<JsonNode> transcript) {
        AssertionResult correctness = evaluateHandoff(spec, transcript);
        if (!correctness.passed) {
            return fail("handoff_timing", true, "Handoff timing failed: " + correctness.message);
        }
        Integer minTurn = intOrNull(spec, "minTurn");
        if (minTurn == null) {
            return pass("handoff_timing", true, "Handoff timing satisfied (correctness only)");
        }
        int handoffTurn =
                transcript.stream()
                        .filter(turn -> turn.path("handoffRequired").asBoolean(false))
                        .mapToInt(turn -> turn.path("turn").asInt(-1))
                        .findFirst()
                        .orElse(-1);
        if (handoffTurn < minTurn) {
            return fail(
                    "handoff_timing",
                    true,
                    "Handoff at turn " + handoffTurn + " is before minTurn " + minTurn);
        }
        return pass(
                "handoff_timing",
                true,
                "Handoff at turn "
                        + handoffTurn
                        + " within ["
                        + minTurn
                        + ","
                        + spec.path("maxTurn").asInt(-1)
                        + "]");
    }

    private AssertionResult evaluateOperatorReply(JsonNode spec, JsonNode operatorOutcome) {
        boolean expected = spec.path("expectedOperatorReply").asBoolean(false);
        String content = operatorOutcome.path("content").asText("");
        boolean observed = operatorOutcome.path("delivered").asBoolean(false) && !content.isBlank();
        if (observed == expected) {
            return pass(
                    "operator_reply",
                    true,
                    "Operator reply observed=" + observed + " matches expected=" + expected);
        }
        return fail(
                "operator_reply",
                true,
                "Operator reply observed=" + observed + " expected=" + expected);
    }

    private AssertionResult evaluateLeadCapture(JsonNode spec, List<JsonNode> transcript) {
        JsonNode lastWithLead = null;
        for (JsonNode turn : transcript) {
            if (turn.path("leadCaptured").asBoolean(false) || !turn.path("lead").isEmpty()) {
                lastWithLead = turn;
            }
        }
        List<String> required = readStringList(spec.path("requiredLeadFields"));
        if (lastWithLead == null) {
            if (required.isEmpty()) {
                return pass("lead_capture", false, "No lead required and none captured");
            }
            return fail("lead_capture", true, "Expected lead fields but no lead captured");
        }
        JsonNode lead = lastWithLead.path("lead");
        List<String> missing = new ArrayList<>();
        for (String field : required) {
            if (resolveLeadField(lead, field) == null) {
                missing.add(field);
            }
        }
        if (missing.isEmpty()) {
            return pass("lead_capture", true, "Lead contains required fields: " + required);
        }
        return fail("lead_capture", true, "Lead missing required fields: " + missing);
    }

    private AssertionResult evaluateQuality(JsonNode spec, List<JsonNode> transcript) {
        List<JsonNode> scope = turnsForEvaluation(spec, transcript);
        if (scope.isEmpty()) {
            return fail("quality", true, "No transcript turns to evaluate");
        }
        List<String> failures = new ArrayList<>();
        List<String> forbidden = readStringList(spec.path("forbiddenBotPatterns"));
        Integer maxOutOfDomain = intOrNull(spec, "maxOutOfDomainTurns");
        String minQualityLevel = textOrNull(spec, "minQualityLevel");
        Integer minOverallScore = intOrNull(spec, "minOverallScore");
        for (JsonNode turn : scope) {
            String bot = turn.path("botResponse").asText("");
            if (matchesForbiddenPatterns(bot, forbidden)) {
                failures.add(
                        "turn " + turn.path("turn").asInt() + " bot response matches forbidden pattern");
            }
            if (maxOutOfDomain != null
                    && maxOutOfDomain == 0
                    && "out_of_domain".equalsIgnoreCase(turn.path("intent").asText())) {
                failures.add("turn " + turn.path("turn").asInt() + " intent=out_of_domain");
            }
            JsonNode quality = turn.path("conversationQualityEvaluation");
            if (minQualityLevel != null && !quality.isMissingNode() && !quality.isEmpty()) {
                String level = quality.path("qualityLevel").asText("");
                if (qualityLevelRank(level) < qualityLevelRank(minQualityLevel)) {
                    failures.add(
                            "turn "
                                    + turn.path("turn").asInt()
                                    + " qualityLevel="
                                    + level
                                    + " below "
                                    + minQualityLevel);
                }
            }
            if (minOverallScore != null && quality.has("overallScore")) {
                int numeric = quality.path("overallScore").asInt(-1);
                if (numeric >= 0 && numeric < minOverallScore) {
                    failures.add(
                            "turn "
                                    + turn.path("turn").asInt()
                                    + " overallScore="
                                    + numeric
                                    + " below "
                                    + minOverallScore);
                }
            }
        }
        if (failures.isEmpty()) {
            return pass("quality", true, "Quality checks passed for " + scope.size() + " turn(s)");
        }
        return fail("quality", true, String.join("; ", failures));
    }

    private AssertionResult evaluateConversationalQuality(JsonNode spec, List<JsonNode> transcript) {
        if (transcript.isEmpty()) {
            return fail("conversational_quality", true, "No transcript turns to evaluate");
        }
        Integer floor = intOrNull(spec, "minConversationScore");
        if (floor == null) {
            return pass("conversational_quality", true, "No conversation score floor configured");
        }
        int avg =
                (int)
                        transcript.stream()
                                .mapToInt(
                                        turn ->
                                                turn.path("conversationQualityEvaluation")
                                                        .path("overallScore")
                                                        .asInt(0))
                                .average()
                                .orElse(0);
        if (avg >= floor) {
            return pass("conversational_quality", true, "conversationScore=" + avg + " >= " + floor);
        }
        return fail("conversational_quality", true, "conversationScore=" + avg + " < " + floor);
    }

    private AssertionResult evaluateRetention(
            JsonNode spec, List<JsonNode> transcript, String dimension) {
        List<JsonNode> scope = turnsForEvaluation(spec, transcript);
        if (scope.isEmpty()) {
            return fail(dimension, true, "No transcript turns to evaluate");
        }
        List<String> failures = new ArrayList<>();
        List<String> forbidden = readStringList(spec.path("forbiddenBotPatterns"));
        List<String> mentions = readStringList(spec.path("requiredBotMentions"));
        for (JsonNode turn : scope) {
            String bot = turn.path("botResponse").asText("").toLowerCase(Locale.ROOT);
            if (matchesForbiddenPatterns(bot, forbidden)) {
                failures.add("turn " + turn.path("turn").asInt() + " lost context: forbidden response");
                continue;
            }
            for (String mention : mentions) {
                if (!bot.contains(mention.toLowerCase(Locale.ROOT))) {
                    failures.add(
                            "turn "
                                    + turn.path("turn").asInt()
                                    + " bot response missing required mention '"
                                    + mention
                                    + "'");
                }
            }
        }
        if (failures.isEmpty()) {
            return pass(dimension, true, dimension + " satisfied for " + scope.size() + " turn(s)");
        }
        return fail(dimension, true, String.join("; ", failures));
    }

    private AssertionResult evaluateNoFalseIntent(
            JsonNode spec, List<JsonNode> transcript, String forbiddenIntent, String dimension) {
        List<JsonNode> scope = turnsForEvaluation(spec, transcript);
        if (scope.isEmpty()) {
            return fail(dimension, true, "No transcript turns to evaluate");
        }
        List<String> failures = new ArrayList<>();
        for (JsonNode turn : scope) {
            if (forbiddenIntent.equalsIgnoreCase(turn.path("intent").asText())) {
                failures.add("turn " + turn.path("turn").asInt() + " false intent=" + forbiddenIntent);
            }
        }
        if (failures.isEmpty()) {
            return pass(dimension, true, "No false " + forbiddenIntent + " for " + scope.size() + " turn(s)");
        }
        return fail(dimension, true, String.join("; ", failures));
    }

    private static List<JsonNode> turnsForEvaluation(JsonNode spec, List<JsonNode> transcript) {
        Integer evaluateTurn = intOrNull(spec, "evaluateTurn");
        if (evaluateTurn == null) {
            return transcript;
        }
        return transcript.stream().filter(turn -> turn.path("turn").asInt() == evaluateTurn).toList();
    }

    private static Object resolveLeadField(JsonNode lead, String field) {
        if (lead == null || lead.isMissingNode() || field == null || field.isBlank()) {
            return null;
        }
        JsonNode value = lead.get(field);
        if (value != null && !value.asText("").isBlank()) {
            return value.asText();
        }
        JsonNode facts = lead.path("leadFacts");
        if (facts.isObject()) {
            JsonNode nested = facts.get(field);
            if (nested != null && !nested.asText("").isBlank()) {
                return nested.asText();
            }
            JsonNode fields = facts.path("fields");
            if (fields.isObject()) {
                JsonNode nestedField = fields.get(field);
                if (nestedField != null && !nestedField.asText("").isBlank()) {
                    return nestedField.asText();
                }
            }
        }
        return null;
    }

    private static boolean matchesForbiddenPatterns(String response, List<String> patterns) {
        if (response == null || patterns.isEmpty()) {
            return false;
        }
        String normalized = response.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (pattern != null
                    && !pattern.isBlank()
                    && normalized.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static int qualityLevelRank(String level) {
        return switch (level == null ? "" : level.toUpperCase(Locale.ROOT)) {
            case "EXCELLENT" -> 4;
            case "GOOD" -> 3;
            case "FAIR" -> 2;
            case "POOR" -> 1;
            default -> 0;
        };
    }

    private static List<JsonNode> readArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : node) {
            items.add(item);
        }
        return List.copyOf(items);
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.asText("").isBlank()) {
                values.add(item.asText());
            }
        }
        return List.copyOf(values);
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("");
        return text.isBlank() ? null : text;
    }

    private static AssertionResult pass(String dimension, boolean blocking, String message) {
        return new AssertionResult(dimension, true, blocking, message);
    }

    private static AssertionResult fail(String dimension, boolean blocking, String message) {
        return new AssertionResult(dimension, false, blocking, message);
    }

    record AssertionResult(String dimension, boolean passed, boolean blocking, String message) {}
}
