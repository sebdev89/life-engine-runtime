package io.lifeengine.runtime.tools.rag;

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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@ConditionalOnProperty(name = "runtime.tools.rag.enabled", havingValue = "true")
public class RagQueryTool implements ToolExecutor {

    public static final String TOOL_ID = "rag.query";

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private final WebClient webClient;
    private final RagProperties properties;
    private final ObjectMapper mapper;

    public RagQueryTool(
            @Qualifier("ragWebClient") WebClient webClient,
            RagProperties properties,
            ObjectMapper mapper) {
        this.webClient = webClient;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public String toolId() {
        return TOOL_ID;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_ID, "RAG knowledge retrieval — queries a vector collection and returns relevant chunks");
    }

    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionRequest request, WorkflowRunContext ctx) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }

        String collectionId = resolveCollectionId(request.input());
        String query = resolveQuery(request.input());
        int topK = resolveTopK(request.input());

        ctx.emit(EventType.TOOL_STARTED,
                Map.of("toolId", TOOL_ID, "collectionId", collectionId, "query", WorkflowRunContext.truncate(query, 120)),
                false);

        if (collectionId.isBlank()) {
            return Mono.fromCallable(() -> errorResult(ctx, collectionId, "no_collection_id", System.currentTimeMillis()))
                    .subscribeOn(Schedulers.boundedElastic());
        }

        if (query.isBlank()) {
            return Mono.fromCallable(() -> errorResult(ctx, collectionId, "empty_query", System.currentTimeMillis()))
                    .subscribeOn(Schedulers.boundedElastic());
        }

        long started = System.currentTimeMillis();
        return postQuery(collectionId, query, topK)
                .timeout(Duration.ofMillis(properties.timeout().toMillis()))
                .publishOn(Schedulers.boundedElastic())
                .map(response -> successResult(ctx, collectionId, response, started))
                .onErrorResume(ex -> Mono.fromCallable(
                        () -> errorResult(ctx, collectionId, ex.getMessage(), started))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<RagQueryResponse> postQuery(String collectionId, String query, int topK) {
        return webClient
                .post()
                .uri("/api/rag/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("collectionId", collectionId, "query", query, "topK", topK))
                .retrieve()
                .bodyToMono(RagQueryResponse.class);
    }

    private ToolExecutionResult successResult(
            WorkflowRunContext ctx, String collectionId, RagQueryResponse response, long started) {
        String json = buildOutput(collectionId, response.chunks() == null ? List.of() : response.chunks(), "ok");
        ctx.putToolOutput(TOOL_ID, json);
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("toolId", TOOL_ID);
        attrs.put("collectionId", collectionId);
        attrs.put("chunkCount", Integer.toString(response.chunks() == null ? 0 : response.chunks().size()));
        attrs.put("latencyMs", Long.toString(System.currentTimeMillis() - started));
        ctx.emit(EventType.TOOL_SUCCEEDED, attrs, false);
        return ToolExecutionResult.ok(TOOL_ID, json);
    }

    private ToolExecutionResult errorResult(WorkflowRunContext ctx, String collectionId, String reason, long started) {
        String json = buildOutput(collectionId, List.of(), "error");
        ctx.putToolOutput(TOOL_ID, json);
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("toolId", TOOL_ID);
        attrs.put("collectionId", collectionId);
        attrs.put("latencyMs", Long.toString(System.currentTimeMillis() - started));
        attrs.put("error", WorkflowRunContext.truncate(reason, 240));
        ctx.emit(EventType.TOOL_FAILED, attrs, false);
        return ToolExecutionResult.ok(TOOL_ID, json);
    }

    private String buildOutput(String collectionId, List<ChunkResponse> chunks, String status) {
        ObjectNode out = mapper.createObjectNode();
        out.put("collectionId", collectionId);
        ArrayNode arr = out.putArray("chunks");
        for (ChunkResponse c : chunks) {
            ObjectNode item = arr.addObject();
            item.put("text", c.text() == null ? "" : c.text());
            item.put("score", c.score());
            item.put("citationId", c.citationId() == null ? "" : c.citationId());
            item.put("documentId", c.documentId() == null ? "" : c.documentId());
            item.put("chunkId", c.chunkId() == null ? "" : c.chunkId());
            item.put("title", c.title() == null ? "" : c.title());
        }
        out.put("status", status);
        try {
            return mapper.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            return "{\"collectionId\":\"" + collectionId + "\",\"chunks\":[],\"status\":\"error\"}";
        }
    }

    private String resolveCollectionId(String input) {
        if (input != null && input.trim().startsWith("{")) {
            try {
                JsonNode node = mapper.readTree(input.trim());
                JsonNode cid = node.get("collectionId");
                if (cid != null && cid.isTextual() && !cid.asText().isBlank()) {
                    return cid.asText().trim();
                }
            } catch (Exception ignore) {
                // fall through
            }
        }
        return properties.defaultCollectionId();
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
                if (q == null || !q.isTextual() || q.asText().isBlank()) {
                    q = node.get("question");
                }
                if (q != null && q.isTextual()) {
                    return q.asText();
                }
                return "";
            } catch (Exception ignore) {
                // fall through
            }
        }
        return trimmed;
    }

    private int resolveTopK(String input) {
        if (input != null && input.trim().startsWith("{")) {
            try {
                JsonNode node = mapper.readTree(input.trim());
                JsonNode tk = node.get("topK");
                if (tk != null && tk.isInt()) {
                    int v = tk.asInt();
                    return Math.max(1, Math.min(v, MAX_TOP_K));
                }
            } catch (Exception ignore) {
                // fall through
            }
        }
        return properties.defaultTopK();
    }

    record RagQueryResponse(List<ChunkResponse> chunks) {}

    record ChunkResponse(String text, double score, String citationId, String documentId, String chunkId, String title) {}
}
