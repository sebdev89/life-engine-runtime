package io.lifeengine.runtime.ext.cryptomarketreview.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.cryptomarketreview.tools.GetCryptoIndicatorsTool;
import io.lifeengine.runtime.ext.cryptomarketreview.tools.GetCryptoJournalTool;
import io.lifeengine.runtime.ext.cryptomarketreview.tools.GetCryptoObservationsTool;
import io.lifeengine.runtime.ext.cryptomarketreview.tools.GetCryptoPriceTool;
import io.lifeengine.runtime.ext.cryptomarketreview.tools.GetCryptoTicker24hTool;
import io.lifeengine.runtime.ext.cryptomarketreview.tools.GetCryptoWatchlistTool;
import io.lifeengine.runtime.ext.cryptomarketreview.tools.GetCryptoZonesTool;
import io.lifeengine.runtime.tools.ToolExecutionRequest;
import io.lifeengine.runtime.tools.ToolExecutor;
import io.lifeengine.runtime.tools.ToolRegistry;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple7;

/**
 * Stage 2 — orchestrates the seven cryptobot tools (no LLM) to build a single {@code marketContext}
 * JSON for downstream LLM agents. Reads symbol/timeframe from the upstream intent JSON, fans the
 * seven tool calls out in parallel, then merges them into a single object that the analyst /
 * risk-review / final-summary stages consume.
 *
 * <p>The seven tools emit their own {@code TOOL_STARTED} / {@code TOOL_SUCCEEDED} events inside
 * this agent's {@code STAGE_STARTED} window. That nesting matches the brief's expected event
 * sequence: one workflow-stage envelope around N tool events.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.crypto-market-review.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class LoadCryptoMarketContextAgent implements AgentExecutor {

    public static final String AGENT_ID = "load-crypto-market-context-agent";

    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;

    public LoadCryptoMarketContextAgent(ToolRegistry toolRegistry, ObjectMapper mapper) {
        this.toolRegistry = toolRegistry;
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

        String symbol;
        String timeframe;
        try {
            JsonNode intent = mapper.readTree(request.input());
            symbol = intent.path("symbol").asText("").trim().toUpperCase(java.util.Locale.ROOT);
            timeframe = intent.path("timeframe").asText("1h");
        } catch (Exception e) {
            return failAgent(ctx, "invalid intent JSON: " + e.getMessage(), e);
        }

        if (symbol.isBlank()) {
            return failAgent(ctx, "missing symbol on intent", null);
        }

        String symbolInput;
        try {
            ObjectNode toolInput = mapper.createObjectNode();
            toolInput.put("symbol", symbol);
            toolInput.put("timeframe", timeframe);
            symbolInput = mapper.writeValueAsString(toolInput);
        } catch (Exception e) {
            return failAgent(ctx, "failed to serialize tool input: " + e.getMessage(), e);
        }

        Mono<String> price = callTool(ctx, GetCryptoPriceTool.TOOL_ID, symbolInput);
        Mono<String> ticker = callTool(ctx, GetCryptoTicker24hTool.TOOL_ID, symbolInput);
        Mono<String> watchlist = callTool(ctx, GetCryptoWatchlistTool.TOOL_ID, "{}");
        Mono<String> zones = callTool(ctx, GetCryptoZonesTool.TOOL_ID, symbolInput);
        Mono<String> observations = callTool(ctx, GetCryptoObservationsTool.TOOL_ID, symbolInput);
        Mono<String> journal = callTool(ctx, GetCryptoJournalTool.TOOL_ID, symbolInput);
        Mono<String> indicators = callTool(ctx, GetCryptoIndicatorsTool.TOOL_ID, symbolInput);

        return Mono.zip(price, ticker, watchlist, zones, observations, journal, indicators)
                .flatMap(tuple -> assemble(ctx, symbol, timeframe, tuple))
                .onErrorResume(e -> failAgent(ctx, "tool fan-out failed: " + e.getMessage(), e));
    }

    private Mono<String> callTool(WorkflowRunContext ctx, String toolId, String input) {
        ToolExecutor tool = toolRegistry.require(toolId);
        ToolExecutionRequest req = new ToolExecutionRequest(ctx.runId(), toolId, input, Map.of());
        // Each cryptobot tool drives a WebClient call that signals on Netty event-loop
        // threads. Hop to boundedElastic so the Mono.zip / flatMap / onErrorResume
        // operators downstream — which run blocking ctx.emit calls — never execute on
        // a non-blocking thread.
        return tool.execute(req, ctx)
                .publishOn(Schedulers.boundedElastic())
                .map(r -> r.output() == null ? "" : r.output());
    }

    private Mono<AgentExecutionResult> assemble(
            WorkflowRunContext ctx,
            String symbol,
            String timeframe,
            Tuple7<String, String, String, String, String, String, String> tuple) {
        try {
            // Project the seven raw cryptobot tool outputs into a lean marketContext for the
            // downstream LLM stages. We keep only the fields the analyst / risk-review /
            // final-summary prompts actually use, and drop the noisy persistence metadata
            // (id, createdAt, updatedAt, source, audit timestamps) that otherwise pushes the
            // analyst prompt over the vLLM context window. See
            // docs/operations/local-runbook.md for the rationale; this projection is the
            // single source of truth for what the LLM sees.
            ObjectNode marketContext = mapper.createObjectNode();
            marketContext.put("symbol", symbol);
            marketContext.put("timeframe", timeframe);
            setLeanPrice(marketContext, tuple.getT1());
            marketContext.set("ticker24h", projectTicker24h(parseOrObject(tuple.getT2())));
            marketContext.set("watchlist", projectWatchlist(parseOrArray(tuple.getT3()), symbol));
            marketContext.set("zones", projectArray(parseOrArray(tuple.getT4()), this::projectZone));
            marketContext.set(
                    "observations",
                    projectArray(parseOrArray(tuple.getT5()), this::projectObservation));
            marketContext.set("journal", projectArray(parseOrArray(tuple.getT6()), this::projectJournalEntry));
            marketContext.set(
                    "indicators",
                    projectArray(parseOrArray(tuple.getT7()), this::projectIndicator));

            String json = mapper.writeValueAsString(marketContext);
            ctx.putAgentOutput(AGENT_ID, json);

            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("agentId", AGENT_ID);
            attrs.put("symbol", symbol);
            attrs.put("timeframe", timeframe);
            attrs.put("toolsCalled", "7");
            attrs.put("zonesCount", Integer.toString(marketContext.get("zones").size()));
            attrs.put("observationsCount", Integer.toString(marketContext.get("observations").size()));
            attrs.put("indicatorsCount", Integer.toString(marketContext.get("indicators").size()));
            attrs.put("contextChars", Integer.toString(json.length()));
            ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);

            return Mono.just(AgentExecutionResult.ok(AGENT_ID, json));
        } catch (Exception e) {
            return failAgent(ctx, "failed to assemble marketContext: " + e.getMessage(), e);
        }
    }

    private void setLeanPrice(ObjectNode marketContext, String priceJson) {
        JsonNode node = parseOrObject(priceJson);
        JsonNode price = node.get("price");
        if (price != null && price.isNumber()) {
            marketContext.set("price", price);
        } else {
            marketContext.putNull("price");
        }
    }

    private ObjectNode projectTicker24h(JsonNode node) {
        ObjectNode out = mapper.createObjectNode();
        copyNumeric(node, out, "priceChangePct24h");
        copyNumeric(node, out, "volumeBase24h");
        return out;
    }

    private ArrayNode projectWatchlist(JsonNode list, String currentSymbol) {
        ArrayNode out = mapper.createArrayNode();
        if (list == null || !list.isArray()) {
            return out;
        }
        String target = currentSymbol == null ? "" : currentSymbol.toUpperCase(Locale.ROOT);
        for (JsonNode entry : list) {
            String entrySymbol = entry.path("symbol").asText("").toUpperCase(Locale.ROOT);
            if (!entrySymbol.equals(target)) {
                continue; // single-symbol review: drop unrelated rows.
            }
            ObjectNode lean = mapper.createObjectNode();
            copyText(entry, lean, "symbol");
            copyText(entry, lean, "displayName");
            copyText(entry, lean, "assetType");
            out.add(lean);
        }
        return out;
    }

    private ObjectNode projectZone(JsonNode entry) {
        ObjectNode lean = mapper.createObjectNode();
        copyText(entry, lean, "zoneKind");
        copyNumeric(entry, lean, "lowerBound");
        copyNumeric(entry, lean, "upperBound");
        copyNumeric(entry, lean, "confidence");
        copyText(entry, lean, "label");
        return lean;
    }

    private ObjectNode projectObservation(JsonNode entry) {
        ObjectNode lean = mapper.createObjectNode();
        // observedAt is the only timestamp the analyst actually needs (freshness signal).
        copyText(entry, lean, "observedAt");
        copyNumeric(entry, lean, "lastPrice");
        copyNumeric(entry, lean, "changePct24h");
        copyNumeric(entry, lean, "liquidityScore");
        copyText(entry, lean, "regime");
        return lean;
    }

    private ObjectNode projectJournalEntry(JsonNode entry) {
        ObjectNode lean = mapper.createObjectNode();
        copyText(entry, lean, "title");
        copyText(entry, lean, "body");
        copyText(entry, lean, "sentiment");
        return lean;
    }

    private ObjectNode projectIndicator(JsonNode entry) {
        ObjectNode lean = mapper.createObjectNode();
        copyText(entry, lean, "indicatorName");
        copyNumeric(entry, lean, "period");
        copyNumeric(entry, lean, "valueNumeric");
        return lean;
    }

    private ArrayNode projectArray(JsonNode list, java.util.function.Function<JsonNode, ObjectNode> projector) {
        ArrayNode out = mapper.createArrayNode();
        if (list == null || !list.isArray()) {
            return out;
        }
        for (JsonNode entry : list) {
            if (entry == null || !entry.isObject()) {
                continue;
            }
            out.add(projector.apply(entry));
        }
        return out;
    }

    private void copyText(JsonNode src, ObjectNode dst, String field) {
        JsonNode v = src.get(field);
        if (v != null && v.isTextual() && !v.asText().isBlank()) {
            dst.put(field, v.asText());
        }
    }

    private void copyNumeric(JsonNode src, ObjectNode dst, String field) {
        JsonNode v = src.get(field);
        if (v != null && v.isNumber()) {
            dst.set(field, v);
        }
    }

    private JsonNode parseOrObject(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private JsonNode parseOrArray(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createArrayNode();
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (node.isArray()) {
                return node;
            }
            return mapper.createArrayNode();
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    private Mono<AgentExecutionResult> failAgent(WorkflowRunContext ctx, String error, Throwable cause) {
        ctx.emit(EventType.AGENT_FAILED,
                Map.of("agentId", AGENT_ID, "error", WorkflowRunContext.truncate(error, 240)), false);
        if (cause == null) {
            return Mono.error(new IllegalStateException(error));
        }
        return Mono.error(cause instanceof RuntimeException re ? re : new RuntimeException(error, cause));
    }

    /**
     * Exposed only so the integration test can introspect the tool order if needed. Returning
     * the tool ids in the order this agent calls them is useful for SSE assertions.
     */
    public static List<String> toolCallOrder() {
        return List.of(
                GetCryptoPriceTool.TOOL_ID,
                GetCryptoTicker24hTool.TOOL_ID,
                GetCryptoWatchlistTool.TOOL_ID,
                GetCryptoZonesTool.TOOL_ID,
                GetCryptoObservationsTool.TOOL_ID,
                GetCryptoJournalTool.TOOL_ID,
                GetCryptoIndicatorsTool.TOOL_ID);
    }
}
