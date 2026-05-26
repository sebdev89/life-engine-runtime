package io.lifeengine.runtime.ext.cryptomarketreview.stages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Stage 1 — deterministic intent parser. Accepts either:
 * <ul>
 *   <li>JSON input ({@code {"symbol":"BTCUSDT","timeframe":"1h"}}) — fields used as-is,
 *   <li>or free-form text ("Review BTCUSDT and tell me if there is a signal") — extracted
 *       via lightweight regex.
 * </ul>
 *
 * <p>Output (also the {@code request.input()} for stage 2):
 * <pre>{@code
 * {"intent":"MARKET_REVIEW","symbol":"BTCUSDT","timeframe":"1h"}
 * }</pre>
 *
 * <p>No LLM call — keeps stage 1 free of token usage as required by the contract.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.crypto-market-review.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ParseCryptoIntentAgent implements AgentExecutor {

    public static final String AGENT_ID = "parse-crypto-intent-agent";
    public static final String DEFAULT_INTENT = "MARKET_REVIEW";
    public static final String DEFAULT_TIMEFRAME = "1h";

    /** Matches anything that looks like a trading symbol — 4-16 alphanumeric chars, upper-case once normalised. */
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("\\b([A-Za-z]{2,10}(?:USDT|USD|EUR|BUSD)?)\\b");

    private static final Pattern TIMEFRAME_PATTERN = Pattern.compile("\\b(\\d{1,3}[mhd])\\b", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper;

    public ParseCryptoIntentAgent(ObjectMapper mapper) {
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
            ObjectNode parsed = parse(request.input());
            String json = mapper.writeValueAsString(parsed);
            ctx.putAgentOutput(AGENT_ID, json);

            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("agentId", AGENT_ID);
            attrs.put("intent", parsed.get("intent").asText());
            attrs.put("symbol", parsed.get("symbol").asText());
            attrs.put("timeframe", parsed.get("timeframe").asText());
            attrs.put("source", parsed.get("source").asText());
            ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);

            return Mono.just(AgentExecutionResult.ok(AGENT_ID, json));
        } catch (Exception e) {
            ctx.emit(
                    EventType.AGENT_FAILED,
                    Map.of("agentId", AGENT_ID, "error", WorkflowRunContext.truncate(e.getMessage(), 240)),
                    false);
            return Mono.error(e);
        }
    }

    ObjectNode parse(String rawInput) throws JsonProcessingException {
        String input = rawInput == null ? "" : rawInput.trim();
        String intent = DEFAULT_INTENT;
        String symbol = "";
        String timeframe = "";
        String source = "free-text";

        if (input.startsWith("{") || input.startsWith("[")) {
            try {
                JsonNode node = mapper.readTree(input);
                if (node.isObject()) {
                    source = "json";
                    if (hasText(node, "intent")) {
                        intent = node.get("intent").asText().trim();
                    }
                    if (hasText(node, "symbol")) {
                        symbol = node.get("symbol").asText().trim();
                    }
                    if (hasText(node, "timeframe")) {
                        timeframe = node.get("timeframe").asText().trim();
                    }
                }
            } catch (Exception ignore) {
                source = "free-text";
            }
        }
        if (symbol.isBlank()) {
            symbol = extractSymbol(input);
        }
        if (timeframe.isBlank()) {
            timeframe = extractTimeframe(input);
        }
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("could not infer a symbol from input: " + truncate(input, 80));
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("intent", intent.toUpperCase(Locale.ROOT));
        out.put("symbol", symbol.trim().toUpperCase(Locale.ROOT));
        out.put("timeframe", timeframe.toLowerCase(Locale.ROOT));
        out.put("source", source);
        return out;
    }

    private static boolean hasText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank();
    }

    static String extractSymbol(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher m = SYMBOL_PATTERN.matcher(text);
        // Prefer a token that contains a known quote suffix (USDT/USD/EUR/BUSD) AND is longer
        // than the suffix itself — so we pick "ETHUSDT" over a bare "USDT" placeholder.
        while (m.find()) {
            String candidate = m.group(1).toUpperCase(Locale.ROOT);
            if (hasQuoteSuffix(candidate)) {
                return candidate;
            }
        }
        // Fallback: first alphanumeric token longer than 2 chars that isn't an obvious English word.
        m = SYMBOL_PATTERN.matcher(text);
        while (m.find()) {
            String candidate = m.group(1).toUpperCase(Locale.ROOT);
            if (candidate.length() >= 3 && !isCommonWord(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private static boolean hasQuoteSuffix(String token) {
        if (token == null) {
            return false;
        }
        return (token.endsWith("USDT") && token.length() > 4)
                || (token.endsWith("BUSD") && token.length() > 4)
                || (token.endsWith("USD") && token.length() > 3)
                || (token.endsWith("EUR") && token.length() > 3);
    }

    static String extractTimeframe(String text) {
        if (text == null) {
            return DEFAULT_TIMEFRAME;
        }
        Matcher m = TIMEFRAME_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1).toLowerCase(Locale.ROOT);
        }
        return DEFAULT_TIMEFRAME;
    }

    private static boolean isCommonWord(String token) {
        // Cheap stopword filter so "REVIEW", "AND", "THE" don't get treated as symbols.
        switch (token) {
            case "REVIEW":
            case "TELL":
            case "ME":
            case "AND":
            case "THE":
            case "IF":
            case "THERE":
            case "IS":
            case "A":
            case "SIGNAL":
            case "PLEASE":
            case "ANALYZE":
            case "ANALYSE":
            case "CHECK":
            case "FOR":
            case "GIVE":
                return true;
            default:
                return false;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
