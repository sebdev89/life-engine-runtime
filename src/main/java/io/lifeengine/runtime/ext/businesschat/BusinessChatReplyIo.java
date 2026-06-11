package io.lifeengine.runtime.ext.businesschat;

import io.lifeengine.runtime.ext.businesschat.channels.ChannelType;
import io.lifeengine.runtime.ext.businesschat.channels.InvalidChannelTypeException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflow-input codec for {@link BusinessChatReplyModule#WORKFLOW_ID}.
 */
public final class BusinessChatReplyIo {

    private BusinessChatReplyIo() {}

    public record Customer(String name, String externalId) {}

    public record FaqEntry(String question, String answer) {}

    public record CatalogEntry(
            String itemType,
            String name,
            String description,
            String priceText,
            String availabilityText,
            Map<String, Object> metadata) {}

    public record RetrievedChunk(
            String documentId, String chunkId, String title, String content, double score) {}

    public record BusinessContext(
            String businessName,
            String industry,
            String tone,
            List<String> rules,
            List<FaqEntry> faqs,
            List<CatalogEntry> catalogItems,
            List<RetrievedChunk> retrievedChunks) {}

    public record HistoryEntry(String customerMessage, String botResponse) {}

    /** Personality and tone hints supplied by business-chat-service (optional). */
    public record BotProfile(
            String businessName, String tone, String personality, String greetingStyle, List<String> rules) {}

    public record Input(
            String channel,
            String botId,
            String conversationId,
            Customer customer,
            String message,
            List<HistoryEntry> conversationHistory,
            BotProfile botProfile,
            BusinessContext businessContext) {}

    public static Input readInput(ObjectMapper mapper, String raw) throws JsonProcessingException {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "input is required: {\"channel\",\"botId\",\"conversationId\",\"customer\",\"message\"}");
        }
        JsonNode root = mapper.readTree(raw);
        if (!root.isObject()) {
            throw new IllegalArgumentException("input must be a JSON object");
        }

        String channel = textOrBlank(root, "channel");
        String botId = textOrBlank(root, "botId");
        String conversationId = textOrBlank(root, "conversationId");
        String message = textOrBlank(root, "message");

        if (channel.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: channel");
        }
        try {
            ChannelType.parse(channel);
        } catch (InvalidChannelTypeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (botId.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: botId");
        }
        if (conversationId.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: conversationId");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: message");
        }

        JsonNode customerNode = root.get("customer");
        if (customerNode == null || !customerNode.isObject()) {
            throw new IllegalArgumentException("missing or invalid field: customer");
        }
        String customerName = textOrBlank(customerNode, "name");
        String externalId = textOrBlank(customerNode, "externalId");
        if (customerName.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: customer.name");
        }
        if (externalId.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: customer.externalId");
        }

        List<HistoryEntry> conversationHistory = parseConversationHistory(root.get("conversationHistory"));
        BotProfile botProfile = parseBotProfile(root.get("botProfile"));
        BusinessContext businessContext = parseBusinessContext(root.get("businessContext"));

        return new Input(
                channel.trim(),
                botId.trim(),
                conversationId.trim(),
                new Customer(customerName.trim(), externalId.trim()),
                message.trim(),
                conversationHistory,
                botProfile,
                businessContext);
    }

    private static List<HistoryEntry> parseConversationHistory(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("invalid field: conversationHistory must be an array");
        }
        List<HistoryEntry> history = new ArrayList<>();
        for (JsonNode turn : node) {
            if (!turn.isObject()) {
                continue;
            }
            String customerMessage = textOrBlank(turn, "customerMessage");
            String botResponse = textOrBlank(turn, "botResponse");
            if (!customerMessage.isBlank() && !botResponse.isBlank()) {
                history.add(new HistoryEntry(customerMessage.trim(), botResponse.trim()));
            }
        }
        return List.copyOf(history);
    }

    private static BotProfile parseBotProfile(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        String businessName = textOrBlank(node, "businessName");
        String tone = textOrBlank(node, "tone");
        String personality = textOrBlank(node, "personality");
        String greetingStyle = textOrBlank(node, "greetingStyle");

        List<String> rules = new ArrayList<>();
        JsonNode rulesNode = node.get("rules");
        if (rulesNode != null && rulesNode.isArray()) {
            for (JsonNode rule : rulesNode) {
                if (rule.isTextual() && !rule.asText().isBlank()) {
                    rules.add(rule.asText().trim());
                }
            }
        }

        if (businessName.isBlank()
                && tone.isBlank()
                && personality.isBlank()
                && greetingStyle.isBlank()
                && rules.isEmpty()) {
            return null;
        }

        return new BotProfile(
                businessName.isBlank() ? null : businessName,
                tone.isBlank() ? null : tone,
                personality.isBlank() ? null : personality,
                greetingStyle.isBlank() ? null : greetingStyle,
                List.copyOf(rules));
    }

    public static Map<String, Object> botProfileForLlm(BotProfile botProfile) {
        if (botProfile == null) {
            return null;
        }
        Map<String, Object> profile = new LinkedHashMap<>();
        if (botProfile.businessName() != null) {
            profile.put("businessName", botProfile.businessName());
        }
        if (botProfile.tone() != null) {
            profile.put("tone", botProfile.tone());
        }
        if (botProfile.personality() != null) {
            profile.put("personality", botProfile.personality());
        }
        if (botProfile.greetingStyle() != null) {
            profile.put("greetingStyle", botProfile.greetingStyle());
        }
        if (!botProfile.rules().isEmpty()) {
            profile.put("rules", botProfile.rules());
        }
        return profile.isEmpty() ? null : Map.copyOf(profile);
    }

    private static BusinessContext parseBusinessContext(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String businessName = textOrBlank(node, "businessName");
        String industry = textOrBlank(node, "industry");
        String tone = textOrBlank(node, "tone");
        if (businessName.isBlank() || tone.isBlank()) {
            return null;
        }

        List<String> rules = new ArrayList<>();
        JsonNode rulesNode = node.get("rules");
        if (rulesNode != null && rulesNode.isArray()) {
            for (JsonNode rule : rulesNode) {
                if (rule.isTextual() && !rule.asText().isBlank()) {
                    rules.add(rule.asText().trim());
                }
            }
        }

        List<FaqEntry> faqs = new ArrayList<>();
        JsonNode faqsNode = node.get("faqs");
        if (faqsNode != null && faqsNode.isArray()) {
            for (JsonNode faq : faqsNode) {
                if (faq.isObject()) {
                    String question = textOrBlank(faq, "question");
                    String answer = textOrBlank(faq, "answer");
                    if (!question.isBlank() && !answer.isBlank()) {
                        faqs.add(new FaqEntry(question, answer));
                    }
                }
            }
        }

        List<CatalogEntry> catalogItems = new ArrayList<>();
        JsonNode catalogNode = node.get("catalogItems");
        if (catalogNode != null && catalogNode.isArray()) {
            for (JsonNode item : catalogNode) {
                if (item.isObject()) {
                    String name = textOrBlank(item, "name");
                    if (!name.isBlank()) {
                        Map<String, Object> metadata = new LinkedHashMap<>();
                        JsonNode metadataNode = item.get("metadata");
                        if (metadataNode != null && metadataNode.isObject()) {
                            metadataNode
                                    .fields()
                                    .forEachRemaining(
                                            entry -> {
                                                if (entry.getValue().isValueNode()) {
                                                    metadata.put(
                                                            entry.getKey(), entry.getValue().asText());
                                                }
                                            });
                        }
                        catalogItems.add(
                                new CatalogEntry(
                                        textOrBlank(item, "itemType"),
                                        name,
                                        textOrBlank(item, "description"),
                                        textOrBlank(item, "priceText"),
                                        textOrBlank(item, "availabilityText"),
                                        Map.copyOf(metadata)));
                    }
                }
            }
        }

        List<RetrievedChunk> retrievedChunks = new ArrayList<>();
        JsonNode chunksNode = node.get("retrievedChunks");
        if (chunksNode != null && chunksNode.isArray()) {
            for (JsonNode chunk : chunksNode) {
                if (!chunk.isObject()) {
                    continue;
                }
                RetrievedChunk parsed = parseRetrievedChunk(chunk);
                if (parsed != null) {
                    retrievedChunks.add(parsed);
                }
            }
        }

        return new BusinessContext(
                businessName,
                industry.isBlank() ? "other" : industry,
                tone,
                List.copyOf(rules),
                List.copyOf(faqs),
                List.copyOf(catalogItems),
                List.copyOf(retrievedChunks));
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
