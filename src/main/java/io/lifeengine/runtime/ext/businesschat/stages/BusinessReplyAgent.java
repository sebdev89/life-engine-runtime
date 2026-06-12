package io.lifeengine.runtime.ext.businesschat.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.agents.LlmAgentSupport;
import io.lifeengine.runtime.agents.StrictAgentJson;
import io.lifeengine.runtime.domain.EventType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.ext.businesschat.BusinessChatReplyIo;
import io.lifeengine.runtime.ext.businesschat.BusinessChatReplyPrompts;
import io.lifeengine.runtime.ext.businesschat.BusinessChatObservabilityEvents;
import io.lifeengine.runtime.ext.businesschat.BusinessChatReplyContractV1;
import io.lifeengine.runtime.ext.businesschat.BusinessChatReplyIo;
import io.lifeengine.runtime.ext.businesschat.BusinessConversationContext;
import io.lifeengine.runtime.ext.businesschat.BusinessKnowledgeService;
import io.lifeengine.runtime.ext.businesschat.BusinessReplyConfidenceService;
import io.lifeengine.runtime.llm.LlmClient;
import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.prompts.PromptTemplate;
import io.lifeengine.runtime.prompts.PromptTemplateRegistry;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Stage 2 — generates the final customer-facing reply from structured business context.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BusinessReplyAgent implements AgentExecutor {

    public static final String AGENT_ID = "business-reply-agent";

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final PromptTemplateRegistry promptTemplateRegistry;
    private final BusinessConversationContext conversationContext;
    private final BusinessReplyConfidenceService confidenceService;
    private final BusinessKnowledgeService knowledgeService;

    public BusinessReplyAgent(
            LlmClient llmClient,
            ObjectMapper mapper,
            PromptTemplateRegistry promptTemplateRegistry,
            BusinessConversationContext conversationContext,
            BusinessReplyConfidenceService confidenceService,
            BusinessKnowledgeService knowledgeService) {
        this.llmClient = llmClient;
        this.mapper = mapper;
        this.promptTemplateRegistry = promptTemplateRegistry;
        this.conversationContext = conversationContext;
        this.confidenceService = confidenceService;
        this.knowledgeService = knowledgeService;
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

        String businessContext = request.input() == null || request.input().isBlank() ? "{}" : request.input();
        boolean contextHandoffRequired;
        String contextHandoffReason;
        try {
            var contextNode = mapper.readTree(businessContext);
            contextHandoffRequired = contextNode.path("handoffRequired").asBoolean(false);
            contextHandoffReason = contextNode.path("handoffReason").asText(null);
        } catch (Exception e) {
            return agentFailed(ctx, e);
        }

        String userInput;
        try {
            var combined = mapper.createObjectNode();
            combined.set("source", mapper.readTree(ctx.input()));
            combined.set("businessContext", mapper.readTree(businessContext));
            userInput = mapper.writeValueAsString(combined);
        } catch (Exception e) {
            return agentFailed(ctx, e);
        }

        PromptTemplate template =
                promptTemplateRegistry.require(
                        BusinessChatReplyPrompts.REPLY_ID, BusinessChatReplyPrompts.VERSION_V1);
        List<LlmMessage> messages =
                List.of(
                        new LlmMessage("system", template.systemMessage()),
                        new LlmMessage("user", userInput));

        return LlmAgentSupport.callLlm(ctx, request.stageId(), AGENT_ID, llmClient, messages, template)
                .flatMap(
                        response -> {
                            try {
                                StrictAgentJson.BusinessReplyOutput reply =
                                        StrictAgentJson.parseBusinessReply(response.content());
                                String canonical;
                                try {
                                    canonical =
                                            finalizeReply(
                                                    ctx.input(),
                                                    businessContext,
                                                    response.content(),
                                                    contextHandoffRequired,
                                                    reply.handoffRequired());
                                } catch (Exception e) {
                                    return agentFailed(ctx, e);
                                }
                                try {
                                    BusinessChatObservabilityEvents.emitResponseGenerated(
                                            ctx,
                                            request.stageId(),
                                            BusinessChatObservabilityEvents.parseInput(mapper, ctx.input()),
                                            reply,
                                            contextHandoffRequired || reply.handoffRequired());
                                } catch (Exception e) {
                                    return agentFailed(ctx, e);
                                }
                                rememberInteraction(ctx, reply.response());
                                ctx.putAgentOutput(AGENT_ID, canonical);

                                Map<String, String> attrs = new LinkedHashMap<>();
                                attrs.put("agentId", AGENT_ID);
                                attrs.put("intent", reply.intent());
                                try {
                                    JsonNode canonicalNode = mapper.readTree(canonical);
                                    attrs.put(
                                            "confidence",
                                            canonicalNode.path("confidence").asText(reply.confidence()));
                                    attrs.put(
                                            "confidenceReason",
                                            canonicalNode.path("confidenceReason").asText(""));
                                } catch (Exception e) {
                                    attrs.put("confidence", reply.confidence());
                                }
                                attrs.put(
                                        "handoffRequired",
                                        Boolean.toString(contextHandoffRequired || reply.handoffRequired()));
                                if (contextHandoffRequired && contextHandoffReason != null) {
                                    attrs.put("handoffReason", contextHandoffReason);
                                }
                                attrs.put("responsePreview", WorkflowRunContext.truncate(reply.response(), 500));
                                attrs.put("structured", WorkflowRunContext.truncate(canonical, 500));
                                ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);

                                return Mono.just(AgentExecutionResult.ok(AGENT_ID, canonical));
                            } catch (IllegalArgumentException e) {
                                return agentParseFailed(ctx, e);
                            }
                        })
                .onErrorResume(
                        error -> {
                            if (error instanceof IllegalArgumentException) {
                                return Mono.error(error);
                            }
                            return agentFailed(ctx, error);
                        });
    }

    private String finalizeReply(
            String sourceInputJson,
            String businessContextJson,
            String rawReply,
            boolean contextHandoffRequired,
            boolean replyHandoffRequired)
            throws Exception {
        ObjectNode node = (ObjectNode) mapper.readTree(StrictAgentJson.canonicalJson(rawReply));
        if (contextHandoffRequired || replyHandoffRequired) {
            node.put("handoffRequired", true);
        }
        BusinessReplyConfidenceService.ReplyConfidence confidence =
                applyDeterministicConfidence(node, sourceInputJson, businessContextJson);
        BusinessChatReplyContractV1.applyToReplyOutput(
                mapper, node, sourceInputJson, businessContextJson, confidence);
        attachSourcesFromContext(node, businessContextJson);
        return mapper.writeValueAsString(node);
    }

    private BusinessReplyConfidenceService.ReplyConfidence applyDeterministicConfidence(
            ObjectNode node, String sourceInputJson, String businessContextJson) throws Exception {
        JsonNode sourceNode = mapper.readTree(sourceInputJson == null || sourceInputJson.isBlank() ? "{}" : sourceInputJson);
        JsonNode contextNode = mapper.readTree(businessContextJson == null || businessContextJson.isBlank() ? "{}" : businessContextJson);
        BusinessChatReplyIo.Input parsed = BusinessChatReplyIo.readInput(mapper, sourceInputJson);
        BusinessKnowledgeService.KnowledgeBase knowledge =
                knowledgeService.resolve(parsed.botId(), parsed.businessContext());
        String message = sourceNode.path("message").asText("");
        String intent = node.path("intent").asText(contextNode.path("intent").asText(""));
        List<Map<String, String>> history = readHistory(sourceNode.path("conversationHistory"));

        BusinessReplyConfidenceService.ReplyConfidence confidence =
                confidenceService.evaluate(
                        message,
                        history,
                        intent,
                        knowledge.faqs(),
                        knowledge.catalogItems(),
                        null);
        node.put("confidence", confidence.level());
        node.put("confidenceReason", confidence.reason());
        return confidence;
    }

    private List<Map<String, String>> readHistory(JsonNode historyNode) {
        if (historyNode == null || !historyNode.isArray()) {
            return List.of();
        }
        List<Map<String, String>> history = new ArrayList<>();
        for (JsonNode turn : historyNode) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("customerMessage", turn.path("customerMessage").asText(""));
            entry.put("botResponse", turn.path("botResponse").asText(""));
            history.add(Map.copyOf(entry));
        }
        return List.copyOf(history);
    }

    private void attachSourcesFromContext(ObjectNode node, String businessContextJson) throws Exception {
        if (node.has("sources") && node.get("sources").isArray() && !node.get("sources").isEmpty()) {
            return;
        }
        JsonNode contextNode = mapper.readTree(businessContextJson);
        JsonNode chunks = contextNode.get("retrievedChunks");
        if (chunks == null || !chunks.isArray() || chunks.isEmpty()) {
            return;
        }
        var sources = mapper.createArrayNode();
        for (JsonNode chunk : chunks) {
            String chunkId = chunk.path("chunkId").asText("");
            if (chunkId.isBlank()) {
                continue;
            }
            var source = mapper.createObjectNode();
            String title = chunk.path("title").asText("");
            source.put("title", title.isBlank() ? "Knowledge" : title);
            source.put("chunkId", chunkId);
            if (chunk.path("score").isNumber()) {
                source.put("score", chunk.path("score").asDouble());
            }
            sources.add(source);
        }
        if (!sources.isEmpty()) {
            node.set("sources", sources);
        }
    }

    /**
     * H2 — Disabled in-memory conversation memory.
     *
     * <p>This call is now a trampoline into the no-op
     * {@link BusinessConversationContext#append(String, String, String)}. The
     * authoritative transcript lives in business-chat-service
     * ({@code MessageR2dbcStore}) and is shipped on every Runtime invocation
     * via {@code conversationHistory}, so the Runtime no longer needs a
     * process-local copy and the previous version's split-memory hazard is
     * eliminated. We keep the call site (rather than removing it) so that a
     * future durable memory bus can be wired in without touching the agent.
     */
    private void rememberInteraction(WorkflowRunContext ctx, String botResponse) {
        try {
            var source = mapper.readTree(ctx.input());
            String conversationId = source.path("conversationId").asText("");
            String customerMessage = source.path("message").asText("");
            if (!conversationId.isBlank() && !customerMessage.isBlank() && !botResponse.isBlank()) {
                conversationContext.append(conversationId, customerMessage, botResponse);
            }
        } catch (Exception ignored) {
            // Conversation memory is best-effort; a malformed source must not fail the run.
        }
    }

    private Mono<AgentExecutionResult> agentParseFailed(WorkflowRunContext ctx, IllegalArgumentException e) {
        String msg = AGENT_ID + ": " + e.getMessage();
        ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
        return Mono.error(new IllegalArgumentException(msg, e));
    }

    private Mono<AgentExecutionResult> agentFailed(WorkflowRunContext ctx, Throwable error) {
        String msg = error.getMessage() == null ? error.toString() : error.getMessage();
        ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
        return Mono.error(error);
    }
}
