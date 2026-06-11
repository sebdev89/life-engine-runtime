package io.lifeengine.runtime.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Strict JSON parsing for demo LLM agents.
 *
 * <p>The JSON grammar itself is <strong>strict</strong> — no trailing commas, no single quotes,
 * no unquoted keys, no silent type coercion. The required fields are validated with explicit
 * "missing or empty field" errors.
 *
 * <p>What the parser <em>does</em> tolerate is transport-layer noise that real LLMs (notably
 * Qwen/Llama instruct models) emit despite system-prompt instructions:
 *
 * <ul>
 *   <li>Triple-backtick code fences, with or without a {@code json} language tag.
 *   <li>A short conversational preamble before the JSON (e.g. {@code "Here is the JSON:"}).
 *   <li>Trailing prose after the closing brace.
 * </ul>
 *
 * <p>Each candidate (raw, unfenced, balanced-extracted) is fed to Jackson in turn. The first
 * one that parses as a JSON <em>object</em> wins. If all fail, we surface the Jackson error
 * from the most informative attempt so debugging stays sharp.
 */
public final class StrictAgentJson {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CATEGORIES = Set.of("INFO", "ACTION", "RISK", "UNKNOWN");

    private StrictAgentJson() {}

    public static SummarizerOutput parseSummarizer(String raw) {
        JsonNode node = requireObject(raw);
        return new SummarizerOutput(
                requireText(node, "incident"),
                requireText(node, "affectedResource"),
                requireText(node, "requestedAction"));
    }

    public static ClassifierOutput parseClassifier(String raw) {
        JsonNode node = requireObject(raw);
        String category = requireText(node, "category").toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException(
                    "category must be one of INFO, ACTION, RISK, UNKNOWN (got: " + category + ")");
        }
        return new ClassifierOutput(category, requireText(node, "reason"));
    }

    public static String canonicalJson(String raw) {
        try {
            return JSON.writeValueAsString(requireObject(raw));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getMessage(), e);
        }
    }

    static JsonNode requireObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty LLM response");
        }
        JsonProcessingException lastError = null;
        for (String candidate : candidatesForParsing(raw)) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                JsonNode node = JSON.readTree(candidate);
                if (!node.isObject()) {
                    // Keep going — a later candidate (e.g. balanced extraction) might still
                    // find an object. If none does, we fall through to a clear error below.
                    continue;
                }
                return node;
            } catch (JsonProcessingException e) {
                lastError = e;
            }
        }
        if (lastError != null) {
            throw new IllegalArgumentException("invalid JSON: " + lastError.getMessage(), lastError);
        }
        throw new IllegalArgumentException("expected JSON object");
    }

    /**
     * Returns ordered candidates for parsing. We try the raw input first so a well-formed
     * response never pays the cost of normalization, then strip a markdown fence, then walk
     * balanced brackets to peel preamble/trailing prose.
     */
    private static List<String> candidatesForParsing(String raw) {
        String stripped = raw.strip();
        List<String> candidates = new ArrayList<>(3);
        candidates.add(stripped);
        String unfenced = stripFirstCodeFence(stripped);
        if (unfenced != null) {
            String trimmed = unfenced.strip();
            if (!trimmed.equals(stripped)) {
                candidates.add(trimmed);
            }
        }
        String balanced = extractFirstBalancedJson(stripped);
        if (balanced != null && !balanced.equals(stripped)) {
            candidates.add(balanced);
        }
        // Also try balanced extraction on the unfenced text — handles the case where the LLM
        // emits "Here is the JSON:\n```json\n{...}\n```\nNote: ..." (preamble + fence + trailer).
        if (unfenced != null) {
            String balancedUnfenced = extractFirstBalancedJson(unfenced);
            if (balancedUnfenced != null
                    && !balancedUnfenced.equals(stripped)
                    && !candidates.contains(balancedUnfenced)) {
                candidates.add(balancedUnfenced);
            }
        }
        return candidates;
    }

    /**
     * If {@code s} contains a triple-backtick fenced block, returns its inner content; otherwise
     * {@code null}. Tolerates an optional language tag ({@code ```json}) on the opening fence.
     * Uses the <em>first</em> opening fence and the <em>last</em> closing fence so backticks
     * embedded inside a JSON string literal don't accidentally truncate the payload.
     */
    private static String stripFirstCodeFence(String s) {
        int open = s.indexOf("```");
        if (open < 0) {
            return null;
        }
        int contentStart = open + 3;
        // Skip optional language tag (alphanumeric or '-'/'_') until newline or whitespace.
        while (contentStart < s.length()) {
            char c = s.charAt(contentStart);
            if (c == '\n' || c == '\r') {
                contentStart++;
                break;
            }
            if (Character.isWhitespace(c)) {
                contentStart++;
                break;
            }
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                contentStart++;
                continue;
            }
            break;
        }
        if (contentStart >= s.length()) {
            return null;
        }
        // Use lastIndexOf to be robust against backticks inside the JSON string literal.
        int close = s.lastIndexOf("```");
        if (close <= contentStart) {
            return null;
        }
        return s.substring(contentStart, close);
    }

    /**
     * Walks {@code s} to find the first balanced JSON object ({@code { ... }}) or array
     * ({@code [ ... ]}), respecting string literals and backslash escapes so braces inside
     * quoted strings do not affect depth tracking. Returns {@code null} if no balanced value
     * is found.
     */
    private static String extractFirstBalancedJson(String s) {
        int start = -1;
        char openChar = 0;
        char closeChar = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                start = i;
                openChar = '{';
                closeChar = '}';
                break;
            }
            if (c == '[') {
                start = i;
                openChar = '[';
                closeChar = ']';
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == openChar) {
                depth++;
            } else if (c == closeChar) {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("missing or empty field: " + field);
        }
        return value.asText().trim();
    }

    public record SummarizerOutput(String incident, String affectedResource, String requestedAction) {}

    public record ClassifierOutput(String category, String reason) {}

    public static ExtractorOutput parseExtractor(String raw) {
        JsonNode node = requireObject(raw);
        return new ExtractorOutput(
                requireText(node, "entity"),
                requireText(node, "value"),
                requireText(node, "confidence"));
    }

    public static EvaluatorOutput parseEvaluator(String raw) {
        JsonNode node = requireObject(raw);
        String verdict = requireText(node, "verdict").toUpperCase(Locale.ROOT);
        if (!Set.of("PASS", "FAIL", "REVIEW").contains(verdict)) {
            throw new IllegalArgumentException("verdict must be PASS, FAIL, or REVIEW (got: " + verdict + ")");
        }
        return new EvaluatorOutput(verdict, requireText(node, "rationale"));
    }

    public record ExtractorOutput(String entity, String value, String confidence) {}

    public record EvaluatorOutput(String verdict, String rationale) {}

    // ----------------------------------------------------------------------------------------
    // Crypto market-review agents (extension verticals re-use the same strict-JSON conventions).
    // ----------------------------------------------------------------------------------------

    private static final Set<String> BIAS_VALUES = Set.of("BULLISH", "BEARISH", "NEUTRAL");
    private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");

    public static CryptoIntentOutput parseCryptoIntent(String raw) {
        JsonNode node = requireObject(raw);
        return new CryptoIntentOutput(
                requireText(node, "intent"),
                requireText(node, "symbol"),
                requireText(node, "timeframe"));
    }

    public static CryptoAnalystOutput parseCryptoAnalyst(String raw) {
        JsonNode node = requireObject(raw);
        String bias = requireText(node, "bias").toUpperCase(Locale.ROOT);
        if (!BIAS_VALUES.contains(bias)) {
            throw new IllegalArgumentException(
                    "bias must be one of BULLISH, BEARISH, NEUTRAL (got: " + bias + ")");
        }
        double confidence = requireDouble(node, "confidence");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be within [0.0, 1.0] (got: " + confidence + ")");
        }
        return new CryptoAnalystOutput(
                bias,
                requireText(node, "summary"),
                requireText(node, "setup"),
                stringArray(node, "invalidations"),
                confidence,
                stringArray(node, "risks"));
    }

    public static CryptoRiskReviewOutput parseCryptoRiskReview(String raw) {
        JsonNode node = requireObject(raw);
        JsonNode approvedNode = node.get("approved");
        if (approvedNode == null || !approvedNode.isBoolean()) {
            throw new IllegalArgumentException("missing or non-boolean field: approved");
        }
        String riskLevel = requireText(node, "riskLevel").toUpperCase(Locale.ROOT);
        if (!RISK_LEVELS.contains(riskLevel)) {
            throw new IllegalArgumentException(
                    "riskLevel must be one of LOW, MEDIUM, HIGH (got: " + riskLevel + ")");
        }
        return new CryptoRiskReviewOutput(
                approvedNode.asBoolean(),
                stringArray(node, "warnings"),
                riskLevel);
    }

    public static CryptoFinalSummaryOutput parseCryptoFinalSummary(String raw) {
        JsonNode node = requireObject(raw);
        return new CryptoFinalSummaryOutput(requireText(node, "response"));
    }

    private static double requireDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            throw new IllegalArgumentException("missing or non-numeric field: " + field);
        }
        return value.asDouble();
    }

    private static java.util.List<String> stringArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        java.util.List<String> out = new java.util.ArrayList<>();
        if (value == null || value.isNull()) {
            return out;
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("field '" + field + "' must be an array of strings");
        }
        for (JsonNode item : value) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (!item.isTextual()) {
                throw new IllegalArgumentException("field '" + field + "' must contain only strings");
            }
            String text = item.asText().trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    public record CryptoIntentOutput(String intent, String symbol, String timeframe) {}

    public record CryptoAnalystOutput(
            String bias,
            String summary,
            String setup,
            java.util.List<String> invalidations,
            double confidence,
            java.util.List<String> risks) {}

    public record CryptoRiskReviewOutput(
            boolean approved, java.util.List<String> warnings, String riskLevel) {}

    public record CryptoFinalSummaryOutput(String response) {}

    // ----------------------------------------------------------------------------------------
    // Dev code-review agents (minimal second vertical).
    // ----------------------------------------------------------------------------------------

    public static DevCodeReviewOutput parseDevCodeReview(String raw) {
        JsonNode node = requireObject(raw);
        String severityHint = requireText(node, "severityHint").toUpperCase(Locale.ROOT);
        if (!RISK_LEVELS.contains(severityHint)) {
            throw new IllegalArgumentException(
                    "severityHint must be one of LOW, MEDIUM, HIGH (got: " + severityHint + ")");
        }
        java.util.List<String> findings = stringArray(node, "findings");
        if (findings.isEmpty()) {
            throw new IllegalArgumentException("findings must be a non-empty array");
        }
        return new DevCodeReviewOutput(findings, severityHint, requireText(node, "notes"));
    }

    public static DevSummaryOutput parseDevSummary(String raw) {
        JsonNode node = requireObject(raw);
        String severity = requireText(node, "severity").toUpperCase(Locale.ROOT);
        if (!RISK_LEVELS.contains(severity)) {
            throw new IllegalArgumentException(
                    "severity must be one of LOW, MEDIUM, HIGH (got: " + severity + ")");
        }
        java.util.List<String> recommendations = stringArray(node, "recommendations");
        if (recommendations.isEmpty()) {
            throw new IllegalArgumentException("recommendations must be a non-empty array");
        }
        return new DevSummaryOutput(severity, requireText(node, "summary"), recommendations);
    }

    public record DevCodeReviewOutput(
            java.util.List<String> findings, String severityHint, String notes) {}

    public record DevSummaryOutput(
            String severity, String summary, java.util.List<String> recommendations) {}

    // ----------------------------------------------------------------------------------------
    // Dev knowledge-answer agents.
    // ----------------------------------------------------------------------------------------

    private static final Set<String> CONFIDENCE_LEVELS = Set.of("HIGH", "MEDIUM", "LOW");

    public static DevKnowledgeAnswerOutput parseDevKnowledgeAnswer(String raw) {
        JsonNode node = requireObject(raw);
        String confidence = requireText(node, "confidence").toUpperCase(Locale.ROOT);
        if (!CONFIDENCE_LEVELS.contains(confidence)) {
            throw new IllegalArgumentException(
                    "confidence must be one of high, medium, low (got: "
                            + node.path("confidence").asText()
                            + ")");
        }
        return new DevKnowledgeAnswerOutput(
                requireText(node, "answer"),
                confidence.toLowerCase(Locale.ROOT),
                optionalDevKnowledgeSources(node));
    }

    private static List<DevKnowledgeSourceOutput> optionalDevKnowledgeSources(JsonNode node) {
        JsonNode sourcesNode = node.get("sources");
        if (sourcesNode == null || sourcesNode.isNull() || !sourcesNode.isArray()) {
            return List.of();
        }
        List<DevKnowledgeSourceOutput> sources = new ArrayList<>();
        for (JsonNode item : sourcesNode) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String title = optionalText(item, "title");
            String chunkId = optionalText(item, "chunkId");
            JsonNode scoreNode = item.get("score");
            if (title == null || chunkId == null) {
                continue;
            }
            String documentId = optionalText(item, "documentId");
            double score = scoreNode != null && scoreNode.isNumber() ? scoreNode.asDouble() : 0.0;
            sources.add(new DevKnowledgeSourceOutput(title, chunkId, documentId, score));
        }
        return List.copyOf(sources);
    }

    public record DevKnowledgeAnswerOutput(
            String answer, String confidence, List<DevKnowledgeSourceOutput> sources) {

        public DevKnowledgeAnswerOutput {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    public record DevKnowledgeSourceOutput(
            String title, String chunkId, String documentId, double score) {}

    // ----------------------------------------------------------------------------------------
    // Business chat agents (Business Agent Platform MVP).
    // ----------------------------------------------------------------------------------------

    private static final Set<String> BUSINESS_INTENTS =
            Set.of(
                    "GREETING",
                    "PRICING",
                    "BOOKING",
                    "LOCATION",
                    "SCHEDULE",
                    "SUPPORT",
                    "COMPLAINT",
                    "HUMAN_HANDOFF");

    public static BusinessContextOutput parseBusinessContext(String raw) {
        JsonNode node = requireObject(raw);
        String intent = normalizeBusinessIntent(requireText(node, "intent"));
        String confidence = requireText(node, "confidence").toUpperCase(Locale.ROOT);
        if (!RISK_LEVELS.contains(confidence)) {
            throw new IllegalArgumentException(
                    "confidence must be one of LOW, MEDIUM, HIGH (got: " + confidence + ")");
        }
        return new BusinessContextOutput(
                intent,
                confidence,
                requireBoolean(node, "handoffRequired"),
                requireBoolean(node, "leadCaptured"),
                requireText(node, "contextNotes"));
    }

    public static BusinessReplyOutput parseBusinessReply(String raw) {
        JsonNode node = requireObject(raw);
        String intent = normalizeBusinessIntent(requireText(node, "intent"));
        String confidence = requireText(node, "confidence").toUpperCase(Locale.ROOT);
        if (!RISK_LEVELS.contains(confidence)) {
            throw new IllegalArgumentException(
                    "confidence must be one of LOW, MEDIUM, HIGH (got: " + confidence + ")");
        }
        return new BusinessReplyOutput(
                requireText(node, "response"),
                intent,
                confidence,
                requireBoolean(node, "handoffRequired"),
                requireBoolean(node, "leadCaptured"),
                requireText(node, "channel"),
                optionalReplySources(node));
    }

    private static List<ReplySourceOutput> optionalReplySources(JsonNode node) {
        JsonNode sourcesNode = node.get("sources");
        if (sourcesNode == null || sourcesNode.isNull() || !sourcesNode.isArray()) {
            return List.of();
        }
        List<ReplySourceOutput> sources = new ArrayList<>();
        for (JsonNode item : sourcesNode) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String title = optionalText(item, "title");
            String chunkId = optionalText(item, "chunkId");
            JsonNode scoreNode = item.get("score");
            if (title == null || chunkId == null || scoreNode == null || !scoreNode.isNumber()) {
                continue;
            }
            sources.add(new ReplySourceOutput(title, chunkId, scoreNode.asDouble()));
        }
        return List.copyOf(sources);
    }

    public static LeadCaptureOutput parseLeadCapture(String raw) {
        JsonNode node = requireObject(raw);
        JsonNode leadDataNode = node.get("leadData");
        if (leadDataNode == null || !leadDataNode.isObject()) {
            throw new IllegalArgumentException("missing or invalid field: leadData");
        }
        LeadData leadData =
                new LeadData(
                        optionalText(leadDataNode, "nombre"),
                        optionalText(leadDataNode, "telefono"),
                        optionalText(leadDataNode, "email"));
        boolean leadCaptured = requireBoolean(node, "leadCaptured");
        if (leadCaptured && leadData.isEmpty()) {
            throw new IllegalArgumentException(
                    "leadCaptured=true requires at least one non-empty leadData field");
        }
        if (!leadCaptured && !leadData.isEmpty()) {
            throw new IllegalArgumentException(
                    "leadCaptured=false requires all leadData fields to be null or empty");
        }
        return new LeadCaptureOutput(leadCaptured, leadData);
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("field '" + field + "' must be a string or null");
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static String normalizeBusinessIntent(String rawIntent) {
        String intent =
                rawIntent.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!BUSINESS_INTENTS.contains(intent)) {
            throw new IllegalArgumentException(
                    "intent must be one of greeting, pricing, booking, location, schedule, support,"
                            + " complaint, human_handoff (got: "
                            + rawIntent
                            + ")");
        }
        return intent.toLowerCase(Locale.ROOT);
    }

    private static boolean requireBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException("missing or non-boolean field: " + field);
        }
        return value.asBoolean();
    }

    public record BusinessContextOutput(
            String intent,
            String confidence,
            boolean handoffRequired,
            boolean leadCaptured,
            String contextNotes) {}

    public record BusinessReplyOutput(
            String response,
            String intent,
            String confidence,
            boolean handoffRequired,
            boolean leadCaptured,
            String channel,
            List<ReplySourceOutput> sources) {

        public BusinessReplyOutput {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    public record ReplySourceOutput(String title, String chunkId, double score) {}

    public record LeadData(String nombre, String telefono, String email) {
        boolean isEmpty() {
            return (nombre == null || nombre.isBlank())
                    && (telefono == null || telefono.isBlank())
                    && (email == null || email.isBlank());
        }
    }

    public record LeadCaptureOutput(boolean leadCaptured, LeadData leadData) {}
}
