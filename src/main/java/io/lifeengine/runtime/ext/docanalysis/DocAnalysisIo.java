package io.lifeengine.runtime.ext.docanalysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Workflow-input codecs for the doc-analysis module ({@code doc.extract.v1}, {@code doc.rank.v1},
 * {@code doc.draft.v1}). Same field-by-field validation style as
 * {@code BusinessChatReplyIo.readInput}: fail loudly with a precise {@code IllegalArgumentException}
 * — no silent defaults on required fields.
 *
 * <p>All vocabulary is generic (documents, candidates, criteria, grounding); the runtime never
 * knows what the documents mean to the caller.
 */
public final class DocAnalysisIo {

    public static final String TASK_EXTRACT = "extract";
    public static final String TASK_RANK = "rank";
    public static final String TASK_DRAFT = "draft";

    private DocAnalysisIo() {}

    /** A document reference with inline content. {@code kind} and {@code title} are optional. */
    public record DocumentRef(String documentId, String kind, String title, String content) {}

    /** Ranking criterion; {@code weight} defaults to 1.0 when absent. */
    public record Criterion(String id, String text, double weight) {}

    public record ExtractInput(
            String instructions, DocumentRef document, JsonNode outputSchema) {}

    public record RankInput(
            String instructions,
            DocumentRef subject,
            List<DocumentRef> candidates,
            List<Criterion> criteria,
            JsonNode outputSchema) {}

    public record DraftInput(
            String instructions,
            DocumentRef subject,
            List<DocumentRef> groundingDocuments,
            String tone,
            JsonNode outputSchema) {}

    // ---------------------------------------------------------------------------------------------
    // doc.extract.v1
    // ---------------------------------------------------------------------------------------------

    public static ExtractInput readExtractInput(ObjectMapper mapper, String raw)
            throws JsonProcessingException {
        JsonNode root = requireRootObject(mapper, raw, TASK_EXTRACT);
        requireTask(root, TASK_EXTRACT);
        String instructions = requireText(root, "instructions");
        DocumentRef document = requireDocument(root.get("document"), "document", true);
        return new ExtractInput(instructions, document, optionalSchema(root));
    }

    // ---------------------------------------------------------------------------------------------
    // doc.rank.v1
    // ---------------------------------------------------------------------------------------------

    public static RankInput readRankInput(ObjectMapper mapper, String raw)
            throws JsonProcessingException {
        JsonNode root = requireRootObject(mapper, raw, TASK_RANK);
        requireTask(root, TASK_RANK);
        String instructions = requireText(root, "instructions");
        DocumentRef subject = requireDocument(root.get("subject"), "subject", false);

        JsonNode candidatesNode = root.get("candidates");
        if (candidatesNode == null || !candidatesNode.isArray() || candidatesNode.isEmpty()) {
            throw new IllegalArgumentException("candidates must be a non-empty array");
        }
        List<DocumentRef> candidates = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        int index = 0;
        for (JsonNode candidate : candidatesNode) {
            DocumentRef parsed = requireDocument(candidate, "candidates[" + index + "]", false);
            if (!seenIds.add(parsed.documentId())) {
                throw new IllegalArgumentException(
                        "duplicate documentId in candidates: " + parsed.documentId());
            }
            candidates.add(parsed);
            index++;
        }

        JsonNode criteriaNode = root.get("criteria");
        if (criteriaNode == null || !criteriaNode.isArray() || criteriaNode.isEmpty()) {
            throw new IllegalArgumentException("criteria must be a non-empty array");
        }
        List<Criterion> criteria = new ArrayList<>();
        index = 0;
        for (JsonNode criterion : criteriaNode) {
            if (criterion == null || !criterion.isObject()) {
                throw new IllegalArgumentException("criteria[" + index + "] must be an object");
            }
            String id = requireText(criterion, "criteria[" + index + "]", "id");
            String text = requireText(criterion, "criteria[" + index + "]", "text");
            double weight = 1.0;
            JsonNode weightNode = criterion.get("weight");
            if (weightNode != null && !weightNode.isNull()) {
                if (!weightNode.isNumber()) {
                    throw new IllegalArgumentException(
                            "criteria[" + index + "].weight must be a number");
                }
                weight = weightNode.asDouble();
                if (weight < 0) {
                    throw new IllegalArgumentException(
                            "criteria[" + index + "].weight must be >= 0");
                }
            }
            criteria.add(new Criterion(id, text, weight));
            index++;
        }

        return new RankInput(
                instructions,
                subject,
                List.copyOf(candidates),
                List.copyOf(criteria),
                optionalSchema(root));
    }

    // ---------------------------------------------------------------------------------------------
    // doc.draft.v1
    // ---------------------------------------------------------------------------------------------

    public static DraftInput readDraftInput(ObjectMapper mapper, String raw)
            throws JsonProcessingException {
        JsonNode root = requireRootObject(mapper, raw, TASK_DRAFT);
        requireTask(root, TASK_DRAFT);
        String instructions = requireText(root, "instructions");
        DocumentRef subject = requireDocument(root.get("subject"), "subject", false);

        JsonNode groundingNode = root.get("groundingDocuments");
        if (groundingNode == null || !groundingNode.isArray() || groundingNode.isEmpty()) {
            throw new IllegalArgumentException("groundingDocuments must be a non-empty array");
        }
        List<DocumentRef> groundingDocuments = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        int index = 0;
        for (JsonNode grounding : groundingNode) {
            DocumentRef parsed =
                    requireDocument(grounding, "groundingDocuments[" + index + "]", false);
            if (!seenIds.add(parsed.documentId())) {
                throw new IllegalArgumentException(
                        "duplicate documentId in groundingDocuments: " + parsed.documentId());
            }
            groundingDocuments.add(parsed);
            index++;
        }

        String tone = textOrBlank(root, "tone").trim();
        return new DraftInput(
                instructions,
                subject,
                List.copyOf(groundingDocuments),
                tone.isBlank() ? null : tone,
                optionalSchema(root));
    }

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    private static JsonNode requireRootObject(ObjectMapper mapper, String raw, String task)
            throws JsonProcessingException {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "input is required: {\"task\":\"" + task + "\",\"instructions\",...}");
        }
        JsonNode root = mapper.readTree(raw);
        if (!root.isObject()) {
            throw new IllegalArgumentException("input must be a JSON object");
        }
        return root;
    }

    private static void requireTask(JsonNode root, String expected) {
        String task = textOrBlank(root, "task").trim();
        if (task.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: task");
        }
        if (!expected.equals(task)) {
            throw new IllegalArgumentException(
                    "task must be \"" + expected + "\" (got: " + task + ")");
        }
    }

    private static DocumentRef requireDocument(JsonNode node, String path, boolean withKindTitle) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("missing or invalid field: " + path);
        }
        String documentId = textOrBlank(node, "documentId").trim();
        if (documentId.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: " + path + ".documentId");
        }
        String content = textOrBlank(node, "content");
        if (content.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: " + path + ".content");
        }
        String kind = withKindTitle ? emptyToNull(textOrBlank(node, "kind")) : null;
        String title = withKindTitle ? emptyToNull(textOrBlank(node, "title")) : null;
        return new DocumentRef(documentId, kind, title, content);
    }

    private static JsonNode optionalSchema(JsonNode root) {
        JsonNode schema = root.get("outputSchema");
        if (schema == null || schema.isNull()) {
            return null;
        }
        if (!schema.isObject()) {
            throw new IllegalArgumentException("outputSchema must be a JSON object when present");
        }
        return schema;
    }

    private static String requireText(JsonNode node, String field) {
        String value = textOrBlank(node, field).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: " + field);
        }
        return value;
    }

    private static String requireText(JsonNode node, String path, String field) {
        String value = textOrBlank(node, field).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: " + path + "." + field);
        }
        return value;
    }

    private static String emptyToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String textOrBlank(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return "";
        }
        return value.asText();
    }
}
