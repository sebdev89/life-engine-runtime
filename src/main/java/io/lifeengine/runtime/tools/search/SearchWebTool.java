package io.lifeengine.runtime.tools.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.tools.ToolDefinition;
import io.lifeengine.runtime.tools.ToolExecutionRequest;
import io.lifeengine.runtime.tools.ToolExecutionResult;
import io.lifeengine.runtime.tools.ToolExecutor;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Web search capability tool. Registered in {@link io.lifeengine.runtime.tools.ToolRegistry}
 * only when {@code runtime.tools.search.enabled=true}.
 *
 * <p>Output JSON shape:
 * <pre>
 * {
 *   "results": [{"title":"...","url":"...","snippet":"..."}],
 *   "provider": "mock|tavily",
 *   "status": "ok|disabled|error"
 * }
 * </pre>
 *
 * <p>The tool never propagates exceptions to the caller — a degraded provider returns
 * {@code status=disabled} or {@code status=error} with an empty results array so the
 * workflow stage can continue without search context.
 */
@Component
@ConditionalOnProperty(name = "runtime.tools.search.enabled", havingValue = "true")
public class SearchWebTool implements ToolExecutor {

    public static final String TOOL_ID = "search.web";

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_RESULTS_LIMIT = 20;

    private final SearchProvider provider;
    private final ObjectMapper mapper;

    public SearchWebTool(SearchProvider provider, ObjectMapper mapper) {
        this.provider = provider;
        this.mapper = mapper;
    }

    @Override
    public String toolId() {
        return TOOL_ID;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_ID, "Web search — returns top N results with title, url, and snippet");
    }

    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionRequest request, WorkflowRunContext ctx) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }

        String query = resolveQuery(request.input());
        int maxResults = resolveMaxResults(request.input());

        ctx.emit(EventType.TOOL_STARTED,
                Map.of("toolId", TOOL_ID, "query", WorkflowRunContext.truncate(query, 120)), false);

        if (!provider.isAvailable()) {
            return Mono.fromCallable(() -> disabledResult(ctx))
                    .subscribeOn(Schedulers.boundedElastic());
        }

        long started = System.currentTimeMillis();
        return provider.search(query, maxResults)
                .publishOn(Schedulers.boundedElastic())
                .map(results -> successResult(ctx, results, started))
                .onErrorResume(ex -> Mono.fromCallable(() -> errorResult(ctx, ex, started))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private ToolExecutionResult successResult(WorkflowRunContext ctx, List<SearchResult> results, long started) {
        String json = buildOutput(results, provider.name(), "ok");
        ctx.putToolOutput(TOOL_ID, json);
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("toolId", TOOL_ID);
        attrs.put("provider", provider.name());
        attrs.put("resultCount", Integer.toString(results.size()));
        attrs.put("latencyMs", Long.toString(System.currentTimeMillis() - started));
        ctx.emit(EventType.TOOL_SUCCEEDED, attrs, false);
        return ToolExecutionResult.ok(TOOL_ID, json);
    }

    private ToolExecutionResult disabledResult(WorkflowRunContext ctx) {
        String json = buildOutput(List.of(), provider.name(), "disabled");
        ctx.putToolOutput(TOOL_ID, json);
        ctx.emit(EventType.TOOL_SUCCEEDED,
                Map.of("toolId", TOOL_ID, "provider", provider.name(), "status", "disabled"), false);
        return ToolExecutionResult.ok(TOOL_ID, json);
    }

    private ToolExecutionResult errorResult(WorkflowRunContext ctx, Throwable ex, long started) {
        String json = buildOutput(List.of(), provider.name(), "error");
        ctx.putToolOutput(TOOL_ID, json);
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("toolId", TOOL_ID);
        attrs.put("provider", provider.name());
        attrs.put("latencyMs", Long.toString(System.currentTimeMillis() - started));
        attrs.put("error", WorkflowRunContext.truncate(ex.getMessage(), 240));
        ctx.emit(EventType.TOOL_FAILED, attrs, false);
        return ToolExecutionResult.ok(TOOL_ID, json);
    }

    private String buildOutput(List<SearchResult> results, String providerName, String status) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode arr = out.putArray("results");
        for (SearchResult r : results) {
            ObjectNode item = arr.addObject();
            item.put("title", r.title());
            item.put("url", r.url());
            item.put("snippet", r.snippet());
        }
        out.put("provider", providerName);
        out.put("status", status);
        try {
            return mapper.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            return "{\"results\":[],\"provider\":\"" + providerName + "\",\"status\":\"error\"}";
        }
    }

    private String resolveQuery(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String trimmed = input.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode node = mapper.readTree(trimmed);
                JsonNode q = node.get("query");
                if (q != null && q.isTextual()) {
                    return q.asText();
                }
            } catch (Exception ignore) {
                // fall through
            }
        }
        return trimmed;
    }

    private int resolveMaxResults(String input) {
        if (input == null || input.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            JsonNode node = mapper.readTree(input.trim());
            JsonNode mr = node.get("maxResults");
            if (mr != null && mr.isInt()) {
                int v = mr.asInt();
                return Math.max(1, Math.min(v, MAX_RESULTS_LIMIT));
            }
        } catch (Exception ignore) {
            // fall through
        }
        return DEFAULT_MAX_RESULTS;
    }
}
