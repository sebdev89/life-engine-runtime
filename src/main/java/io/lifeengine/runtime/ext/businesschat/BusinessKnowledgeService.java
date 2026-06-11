package io.lifeengine.runtime.ext.businesschat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Resolves business knowledge for a workflow run. Prefers {@link BusinessChatReplyIo.BusinessContext}
 * supplied by business-chat-service; falls back to in-memory {@link BusinessBotRegistry} for
 * direct runtime demos.
 */
@Service
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BusinessKnowledgeService {

    private final BusinessBotRegistry botRegistry;

    public BusinessKnowledgeService(BusinessBotRegistry botRegistry) {
        this.botRegistry = botRegistry;
    }

    public KnowledgeBase require(String botId) {
        BusinessBotDefinition bot = botRegistry.require(botId);
        return fromRegistry(bot);
    }

    public KnowledgeBase resolve(String botId, BusinessChatReplyIo.BusinessContext businessContext) {
        if (businessContext != null) {
            return fromBusinessContext(botId, businessContext);
        }
        return require(botId);
    }

    public Map<String, Object> profileForLlm(KnowledgeBase knowledge) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("businessName", knowledge.businessName());
        profile.put("industry", knowledge.industry());
        profile.put("rules", knowledge.rules());
        profile.put("tone", knowledge.tone());
        profile.put("faqs", knowledge.faqs());
        profile.put("catalogItems", knowledge.catalogItems());
        profile.put("retrievedChunks", knowledge.retrievedChunks());
        profile.put("knowledgeBase", knowledge.text());
        return Map.copyOf(profile);
    }

    private static KnowledgeBase fromRegistry(BusinessBotDefinition bot) {
        return new KnowledgeBase(
                bot.botId(),
                bot.businessName(),
                "other",
                bot.tone(),
                List.copyOf(bot.rules()),
                List.copyOf(bot.faqs()),
                List.of(),
                List.of(),
                renderText(
                        bot.businessName(),
                        bot.tone(),
                        bot.rules(),
                        bot.faqs(),
                        List.of(),
                        List.of()));
    }

    private static KnowledgeBase fromBusinessContext(
            String botId, BusinessChatReplyIo.BusinessContext context) {
        List<BusinessBotDefinition.Faq> faqs =
                context.faqs().stream()
                        .map(f -> new BusinessBotDefinition.Faq(f.question(), f.answer()))
                        .toList();
        List<Map<String, String>> catalog =
                context.catalogItems().stream()
                        .map(
                                item -> {
                                    Map<String, String> entry = new LinkedHashMap<>();
                                    entry.put("itemType", item.itemType());
                                    entry.put("name", item.name());
                                    entry.put("description", item.description());
                                    entry.put("priceText", item.priceText());
                                    entry.put("availabilityText", item.availabilityText());
                                    return Map.copyOf(entry);
                                })
                        .toList();
        List<Map<String, Object>> retrievedChunks = toRetrievedChunkMaps(context.retrievedChunks());
        return new KnowledgeBase(
                botId,
                context.businessName(),
                context.industry(),
                context.tone(),
                context.rules(),
                faqs,
                catalog,
                retrievedChunks,
                renderText(
                        context.businessName(),
                        context.tone(),
                        context.rules(),
                        faqs,
                        catalog,
                        retrievedChunks));
    }

    private static List<Map<String, Object>> toRetrievedChunkMaps(
            List<BusinessChatReplyIo.RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> maps = new java.util.ArrayList<>(chunks.size());
        for (BusinessChatReplyIo.RetrievedChunk chunk : chunks) {
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

    private static String renderText(
            String businessName,
            String tone,
            List<String> rules,
            List<BusinessBotDefinition.Faq> faqs,
            List<Map<String, String>> catalogItems,
            List<Map<String, Object>> retrievedChunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Negocio: ").append(businessName).append('\n');
        if (!retrievedChunks.isEmpty()) {
            sb.append("Fragmentos recuperados (priorizar sobre FAQs/catálogo cuando respondan la consulta):\n");
            for (Map<String, Object> chunk : retrievedChunks) {
                sb.append("- [")
                        .append(chunk.getOrDefault("title", "Knowledge"))
                        .append("] score=")
                        .append(chunk.getOrDefault("score", 0.0))
                        .append(": ")
                        .append(chunk.getOrDefault("content", ""))
                        .append('\n');
            }
        }
        if (!catalogItems.isEmpty()) {
            sb.append("Catálogo:\n");
            for (Map<String, String> item : catalogItems) {
                sb.append("- ")
                        .append(item.getOrDefault("name", ""))
                        .append(" (")
                        .append(item.getOrDefault("itemType", ""))
                        .append("): ")
                        .append(item.getOrDefault("priceText", ""))
                        .append(" — ")
                        .append(item.getOrDefault("availabilityText", ""))
                        .append('\n');
                String description = item.getOrDefault("description", "");
                if (!description.isBlank()) {
                    sb.append("  ").append(description).append('\n');
                }
            }
        }
        sb.append("Preguntas frecuentes:\n");
        for (BusinessBotDefinition.Faq faq : faqs) {
            sb.append("- ").append(faq.question()).append(" → ").append(faq.answer()).append('\n');
        }
        sb.append("Reglas:\n");
        for (String rule : rules) {
            sb.append("- ").append(rule).append('\n');
        }
        sb.append("Tono:\n- ").append(tone);
        return sb.toString().strip();
    }

    public record KnowledgeBase(
            String botId,
            String businessName,
            String industry,
            String tone,
            List<String> rules,
            List<BusinessBotDefinition.Faq> faqs,
            List<Map<String, String>> catalogItems,
            List<Map<String, Object>> retrievedChunks,
            String text) {}
}
