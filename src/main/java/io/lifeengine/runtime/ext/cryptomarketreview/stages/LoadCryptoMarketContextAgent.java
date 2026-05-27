package io.lifeengine.runtime.ext.cryptomarketreview.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            ObjectNode marketContext = mapper.createObjectNode();
            marketContext.put("symbol", symbol);
            marketContext.put("timeframe", timeframe);
            marketContext.set("price", parseOrObject(tuple.getT1()));
            marketContext.set("ticker24h", parseOrObject(tuple.getT2()));
            marketContext.set("watchlist", parseOrArray(tuple.getT3()));
            marketContext.set("zones", parseOrArray(tuple.getT4()));
            marketContext.set("observations", parseOrArray(tuple.getT5()));
            marketContext.set("journal", parseOrArray(tuple.getT6()));
            marketContext.set("indicators", parseOrArray(tuple.getT7()));

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
            ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);

            return Mono.just(AgentExecutionResult.ok(AGENT_ID, json));
        } catch (Exception e) {
            return failAgent(ctx, "failed to assemble marketContext: " + e.getMessage(), e);
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
