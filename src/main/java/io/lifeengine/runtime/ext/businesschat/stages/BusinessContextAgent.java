package io.lifeengine.runtime.ext.businesschat.stages;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.agents.LlmAgentSupport;
import io.lifeengine.runtime.agents.StrictAgentJson;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.businesschat.BusinessChatReplyIo;
import io.lifeengine.runtime.ext.businesschat.BusinessChatReplyPrompts;
import io.lifeengine.runtime.ext.businesschat.BusinessChatObservabilityEvents;
import io.lifeengine.runtime.ext.businesschat.BusinessConversationContext;
import io.lifeengine.runtime.ext.businesschat.BusinessFaqMatcher;
import io.lifeengine.runtime.ext.businesschat.BusinessHandoffService;
import io.lifeengine.runtime.ext.businesschat.BusinessReplyConfidenceService;
import io.lifeengine.runtime.ext.businesschat.BusinessKnowledgeService;
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
 * Stage 1 — normalizes inbound message, loads business knowledge, and classifies customer intent
 * for the reply stage.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BusinessContextAgent implements AgentExecutor {

    public static final String AGENT_ID = "business-context-agent";

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final PromptTemplateRegistry promptTemplateRegistry;
    private final BusinessKnowledgeService knowledgeService;
    private final BusinessConversationContext conversationContext;
    private final BusinessHandoffService handoffService;
    private final BusinessReplyConfidenceService confidenceService;

    public BusinessContextAgent(
            LlmClient llmClient,
            ObjectMapper mapper,
            PromptTemplateRegistry promptTemplateRegistry,
            BusinessKnowledgeService knowledgeService,
            BusinessConversationContext conversationContext,
            BusinessHandoffService handoffService,
            BusinessReplyConfidenceService confidenceService) {
        this.llmClient = llmClient;
        this.mapper = mapper;
        this.promptTemplateRegistry = promptTemplateRegistry;
        this.knowledgeService = knowledgeService;
        this.conversationContext = conversationContext;
        this.handoffService = handoffService;
        this.confidenceService = confidenceService;
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

        BusinessChatReplyIo.Input parsed;
        BusinessKnowledgeService.KnowledgeBase knowledge;
        try {
            parsed = BusinessChatReplyIo.readInput(mapper, request.input());
            knowledge = knowledgeService.resolve(parsed.botId(), parsed.businessContext());
        } catch (Exception e) {
            return agentFailed(ctx, e);
        }

        BusinessChatObservabilityEvents.emitConversationStarted(ctx, request.stageId(), parsed);
        BusinessChatObservabilityEvents.emitKnowledgeLookup(ctx, request.stageId(), parsed, knowledge);

        List<BusinessConversationContext.Interaction> conversationHistory =
                resolveConversationHistory(parsed);

        String userInput;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("channel", parsed.channel());
            payload.put("botId", parsed.botId());
            payload.put("conversationId", parsed.conversationId());
            payload.put(
                    "customer",
                    Map.of(
                            "name", parsed.customer().name(),
                            "externalId", parsed.customer().externalId()));
            payload.put("message", parsed.message());
            payload.put("conversationHistory", toHistoryMaps(conversationHistory));
            Map<String, Object> botProfile = BusinessChatReplyIo.botProfileForLlm(parsed.botProfile());
            if (botProfile != null) {
                payload.put("botProfile", botProfile);
            }
            payload.put("businessProfile", knowledgeService.profileForLlm(knowledge));
            userInput = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return agentFailed(ctx, e);
        }

        PromptTemplate template =
                promptTemplateRegistry.require(
                        BusinessChatReplyPrompts.CONTEXT_ID, BusinessChatReplyPrompts.VERSION_V1);
        List<LlmMessage> messages =
                List.of(
                        new LlmMessage("system", template.systemMessage()),
                        new LlmMessage("user", userInput));

        return LlmAgentSupport.callLlm(ctx, request.stageId(), AGENT_ID, llmClient, messages, template)
                .flatMap(
                        response -> {
                            try {
                                StrictAgentJson.BusinessContextOutput context =
                                        StrictAgentJson.parseBusinessContext(response.content());

                                String correctedIntent =
                                        BusinessFaqMatcher.correctIntent(
                                                parsed.message(), context.intent(), knowledge.faqs());

                                BusinessReplyConfidenceService.ReplyConfidence confidence =
                                        confidenceService.evaluate(
                                                parsed.message(),
                                                toHistoryMaps(conversationHistory),
                                                correctedIntent,
                                                knowledge.faqs(),
                                                knowledge.catalogItems(),
                                                null);

                                BusinessHandoffService.HandoffDecision handoff =
                                        handoffService.evaluate(
                                                new BusinessHandoffService.EvaluationRequest(
                                                        parsed.conversationId(),
                                                        parsed.message(),
                                                        correctedIntent,
                                                        confidence.level(),
                                                        knowledge.faqs(),
                                                        toHistoryMaps(conversationHistory)));

                                var combined = mapper.createObjectNode();
                                combined.put("channel", parsed.channel());
                                combined.put("botId", parsed.botId());
                                combined.put("conversationId", parsed.conversationId());
                                combined.put("message", parsed.message());
                                combined.set("conversationHistory", mapper.valueToTree(toHistoryMaps(conversationHistory)));
                                Map<String, Object> botProfile =
                                        BusinessChatReplyIo.botProfileForLlm(parsed.botProfile());
                                if (botProfile != null) {
                                    combined.set("botProfile", mapper.valueToTree(botProfile));
                                }
                                combined.put("intent", correctedIntent);
                                combined.put("confidence", confidence.level());
                                combined.put("confidenceReason", confidence.reason());
                                combined.put("handoffRequired", handoff.handoffRequired());
                                if (handoff.reason() != null) {
                                    combined.put("handoffReason", handoff.reason().name());
                                }
                                combined.put("leadCaptured", context.leadCaptured());
                                combined.put("contextNotes", context.contextNotes());
                                combined.put("knowledgeBase", knowledge.text());
                                combined.put("businessName", knowledge.businessName());
                                combined.put("tone", knowledge.tone());
                                if (!knowledge.retrievedChunks().isEmpty()) {
                                    combined.set(
                                            "retrievedChunks",
                                            mapper.valueToTree(knowledge.retrievedChunks()));
                                }

                                BusinessChatObservabilityEvents.emitIntentDetected(
                                        ctx,
                                        request.stageId(),
                                        parsed,
                                        correctedIntent,
                                        confidence.level(),
                                        confidence.reason(),
                                        handoff.handoffRequired(),
                                        handoff.reason() != null ? handoff.reason().name() : null);

                                String canonical = mapper.writeValueAsString(combined);
                                ctx.putAgentOutput(AGENT_ID, canonical);

                                Map<String, String> attrs = new LinkedHashMap<>();
                                attrs.put("agentId", AGENT_ID);
                                attrs.put("intent", correctedIntent);
                                attrs.put("confidence", confidence.level());
                                attrs.put("confidenceReason", confidence.reason());
                                attrs.put("handoffRequired", Boolean.toString(handoff.handoffRequired()));
                                if (handoff.reason() != null) {
                                    attrs.put("handoffReason", handoff.reason().name());
                                }
                                attrs.put("structured", WorkflowRunContext.truncate(canonical, 500));
                                ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);

                                return Mono.just(AgentExecutionResult.ok(AGENT_ID, canonical));
                            } catch (IllegalArgumentException e) {
                                return agentParseFailed(ctx, e);
                            } catch (Exception e) {
                                return agentFailed(ctx, e);
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

    private List<BusinessConversationContext.Interaction> resolveConversationHistory(
            BusinessChatReplyIo.Input parsed) {
        if (parsed.conversationHistory() != null) {
            return parsed.conversationHistory().stream()
                    .map(
                            entry ->
                                    new BusinessConversationContext.Interaction(
                                            entry.customerMessage(), entry.botResponse()))
                    .toList();
        }
        return conversationContext.history(parsed.conversationId());
    }

    private static List<Map<String, String>> toHistoryMaps(
            List<BusinessConversationContext.Interaction> conversationHistory) {
        List<Map<String, String>> history = new ArrayList<>(conversationHistory.size());
        for (BusinessConversationContext.Interaction interaction : conversationHistory) {
            history.add(
                    Map.of(
                            "customerMessage", interaction.customerMessage(),
                            "botResponse", interaction.botResponse()));
        }
        return history;
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
