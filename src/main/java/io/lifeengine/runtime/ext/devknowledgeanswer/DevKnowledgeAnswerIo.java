package io.lifeengine.runtime.ext.devknowledgeanswer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workflow-input codec for {@link DevKnowledgeAnswerModule#WORKFLOW_ID}. */
public final class DevKnowledgeAnswerIo {

    private DevKnowledgeAnswerIo() {}

    public record RetrievedChunk(
            String documentId, String chunkId, String title, String content, double score) {}

    public record KnowledgeContext(List<RetrievedChunk> retrievedChunks) {
        public KnowledgeContext {
            retrievedChunks = retrievedChunks == null ? List.of() : List.copyOf(retrievedChunks);
        }
    }

    public record Input(String question, KnowledgeContext knowledgeContext) {}

    public static Input readInput(ObjectMapper mapper, String raw) throws JsonProcessingException {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "input is required: {\"question\":\"...\",\"knowledgeContext\":{\"retrievedChunks\":[]}}");
        }
        JsonNode root = mapper.readTree(raw);
        if (!root.isObject()) {
            throw new IllegalArgumentException("input must be a JSON object");
        }
        String question = textOrBlank(root, "question");
        if (question.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: question");
        }
        JsonNode contextNode = root.get("knowledgeContext");
        if (contextNode == null || !contextNode.isObject()) {
            throw new IllegalArgumentException("missing or invalid field: knowledgeContext");
        }
        List<RetrievedChunk> chunks = parseRetrievedChunks(contextNode.get("retrievedChunks"));
        return new Input(question.trim(), new KnowledgeContext(chunks));
    }

    public static String renderKnowledgeBase(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Retrieved knowledge fragments (answer ONLY from these when hasEvidence=true):\n");
        for (RetrievedChunk chunk : chunks) {
            sb.append("- [")
                    .append(chunk.title().isBlank() ? "Knowledge" : chunk.title())
                    .append("] score=")
                    .append(chunk.score())
                    .append(" chunkId=")
                    .append(chunk.chunkId())
                    .append(": ")
                    .append(chunk.content())
                    .append('\n');
        }
        return sb.toString().strip();
    }

    public static List<Map<String, Object>> toChunkMaps(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> maps = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("documentId", chunk.documentId());
            entry.put("chunkId", chunk.chunkId());
            entry.put("title", chunk.title());
            entry.put("content", chunk.content());
            entry.put("score", chunk.score());
            maps.add(Map.copyOf(entry));
        }
        return List.copyOf(maps);
    }

    public static List<RetrievedChunk> parseChunksFromSearchOutput(ObjectMapper mapper, String searchJson) {
        if (searchJson == null || searchJson.isBlank()) return List.of();
        try {
            JsonNode root = mapper.readTree(searchJson);
            if (!"ok".equals(root.path("status").asText())) return List.of();
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) return List.of();
            List<RetrievedChunk> chunks = new ArrayList<>();
            int idx = 0;
            for (JsonNode result : results) {
                String snippet = textOrBlank(result, "snippet");
                if (snippet.isBlank()) continue;
                String title = textOrBlank(result, "title");
                if (title.isBlank()) title = "Web result";
                String url = textOrBlank(result, "url");
                chunks.add(new RetrievedChunk(url, "web-" + idx, title, snippet, 0.0));
                idx++;
            }
            return List.copyOf(chunks);
        } catch (Exception e) {
            return List.of();
        }
    }

    public static List<RetrievedChunk> parseChunksFromRagOutput(ObjectMapper mapper, String ragJson) {
        if (ragJson == null || ragJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(ragJson);
            if (!"ok".equals(root.path("status").asText())) {
                return List.of();
            }
            return parseRetrievedChunks(root.get("chunks"));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<RetrievedChunk> parseRetrievedChunks(JsonNode chunksNode) {
        if (chunksNode == null || !chunksNode.isArray()) {
            return List.of();
        }
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (JsonNode chunk : chunksNode) {
            if (!chunk.isObject()) {
                continue;
            }
            RetrievedChunk parsed = parseRetrievedChunk(chunk);
            if (parsed != null) {
                chunks.add(parsed);
            }
        }
        return List.copyOf(chunks);
    }

    private static RetrievedChunk parseRetrievedChunk(JsonNode chunk) {
        String content = textOrBlank(chunk, "content");
        if (content.isBlank()) {
            content = textOrBlank(chunk, "text");
        }
        if (content.isBlank()) {
            return null;
        }
        String title = textOrBlank(chunk, "title");
        String documentId = textOrBlank(chunk, "documentId");
        String chunkId = textOrBlank(chunk, "chunkId");
        JsonNode metadataNode = chunk.get("metadata");
        if (metadataNode != null && metadataNode.isObject()) {
            if (title.isBlank()) {
                title = textOrBlank(metadataNode, "title");
            }
            if (documentId.isBlank()) {
                documentId = textOrBlank(metadataNode, "documentId");
            }
            if (chunkId.isBlank()) {
                chunkId = textOrBlank(metadataNode, "chunkId");
            }
        }
        if (title.isBlank()) {
            title = "Knowledge";
        }
        double score = chunk.path("score").isNumber() ? chunk.path("score").asDouble() : 0.0;
        return new RetrievedChunk(documentId, chunkId, title, content, score);
    }

    private static String textOrBlank(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return "";
        }
        return value.asText();
    }
}
