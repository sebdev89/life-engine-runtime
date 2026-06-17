package io.lifeengine.runtime.ext.businesschat;

import io.lifeengine.runtime.agents.StrictAgentJson;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.businesschat.intelligence.KnowledgeNeedDetection;
import io.lifeengine.runtime.ext.businesschat.stages.BusinessContextAgent;
import io.lifeengine.runtime.ext.businesschat.stages.BusinessReplyAgent;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.LinkedHashMap;
import java.util.Map;

/** Structured business-chat observability events for Runtime Cockpit timelines. */
public final class BusinessChatObservabilityEvents {

    public static final String SOURCE_RUNTIME = "runtime-core";
    public static final String ATTR_DEDUP_KEY = "dedupKey";
    public static final String ATTR_SOURCE = "observabilitySource";

    private BusinessChatObservabilityEvents() {}

    public static void emitConversationStarted(
            WorkflowRunContext ctx, String stageId, BusinessChatReplyIo.Input input) {
        Map<String, String> attrs = baseAttrs(BusinessContextAgent.AGENT_ID, stageId, input);
        attrs.put(ATTR_DEDUP_KEY, dedupKey(EventType.CONVERSATION_STARTED, stageId));
        attrs.put(ATTR_SOURCE, SOURCE_RUNTIME);
        ctx.emit(EventType.CONVERSATION_STARTED, attrs, false);
    }

    public static void emitKnowledgeLookup(
            WorkflowRunContext ctx,
            String stageId,
            BusinessChatReplyIo.Input input,
            BusinessKnowledgeService.KnowledgeBase knowledge) {
        Map<String, String> attrs = baseAttrs(BusinessContextAgent.AGENT_ID, stageId, input);
        attrs.put("faqCount", Integer.toString(knowledge.faqs().size()));
        attrs.put("catalogItemCount", Integer.toString(knowledge.catalogItems().size()));
        attrs.put("retrievedChunkCount", Integer.toString(knowledge.retrievedChunks().size()));
        attrs.put("ragEnriched", Boolean.toString(!knowledge.retrievedChunks().isEmpty()));
        attrs.put("businessName", knowledge.businessName());
        attrs.put(ATTR_DEDUP_KEY, dedupKey(EventType.KNOWLEDGE_LOOKUP, stageId));
        attrs.put(ATTR_SOURCE, SOURCE_RUNTIME);
        ctx.emit(EventType.KNOWLEDGE_LOOKUP, attrs, false);
    }

    public static void emitIntentDetected(
            WorkflowRunContext ctx,
            String stageId,
            BusinessChatReplyIo.Input input,
            String intent,
            String confidence,
            String confidenceReason,
            boolean handoffRequired,
            String handoffReason) {
        Map<String, String> attrs = baseAttrs(BusinessContextAgent.AGENT_ID, stageId, input);
        attrs.put("intent", intent);
        attrs.put("confidencePreview", confidence);
        attrs.put("confidenceReasonPreview", confidenceReason);
        attrs.put("handoffRequiredPreview", Boolean.toString(handoffRequired));
        if (handoffReason != null && !handoffReason.isBlank()) {
            attrs.put("handoffReasonPreview", handoffReason);
        }
        attrs.put(ATTR_DEDUP_KEY, dedupKey(EventType.INTENT_DETECTED, stageId));
        attrs.put(ATTR_SOURCE, SOURCE_RUNTIME);
        ctx.emit(EventType.INTENT_DETECTED, attrs, false);
    }

    public static void emitResponseGenerated(
            WorkflowRunContext ctx,
            String stageId,
            BusinessChatReplyIo.Input input,
            StrictAgentJson.BusinessReplyOutput reply,
            boolean handoffRequired) {
        Map<String, String> attrs = baseAttrs(BusinessReplyAgent.AGENT_ID, stageId, input);
        attrs.put("intent", reply.intent());
        attrs.put("confidencePreview", reply.confidence());
        attrs.put("handoffRequiredPreview", Boolean.toString(handoffRequired));
        attrs.put("leadCapturedPreview", Boolean.toString(reply.leadCaptured()));
        attrs.put("responsePreview", WorkflowRunContext.truncate(reply.response(), 500));
        attrs.put(ATTR_DEDUP_KEY, dedupKey(EventType.RESPONSE_GENERATED, stageId));
        attrs.put(ATTR_SOURCE, SOURCE_RUNTIME);
        ctx.emit(EventType.RESPONSE_GENERATED, attrs, false);
    }

    public static void emitKnowledgeDetection(
            WorkflowRunContext ctx,
            String stageId,
            BusinessChatReplyIo.Input input,
            KnowledgeNeedDetection detection) {
        Map<String, String> attrs = baseAttrs(BusinessContextAgent.AGENT_ID, stageId, input);
        attrs.put("knowledgeNeeded", Boolean.toString(detection.needsAny()));
        attrs.put("knowledgeStrategy", detection.strategy().name());
        attrs.put("knowledgeReason", detection.reason());
        if (!detection.ragQuery().isBlank()) {
            attrs.put("ragQuery", WorkflowRunContext.truncate(detection.ragQuery(), 120));
        }
        if (!detection.searchQuery().isBlank()) {
            attrs.put("searchQuery", WorkflowRunContext.truncate(detection.searchQuery(), 120));
        }
        attrs.put(ATTR_DEDUP_KEY, dedupKey(EventType.ROUTING_DECISION, stageId));
        attrs.put(ATTR_SOURCE, SOURCE_RUNTIME);
        ctx.emit(EventType.ROUTING_DECISION, attrs, false);
    }

    public static BusinessChatReplyIo.Input parseInput(
            com.fasterxml.jackson.databind.ObjectMapper mapper, String raw) throws Exception {
        return BusinessChatReplyIo.readInput(mapper, raw);
    }

    static String dedupKey(EventType type, String scope) {
        return type.wireName() + ":" + scope;
    }

    private static Map<String, String> baseAttrs(
            String agentId, String stageId, BusinessChatReplyIo.Input input) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("stageId", stageId);
        attrs.put("stageType", "AGENT");
        attrs.put("agentId", agentId);
        attrs.put("botId", input.botId());
        attrs.put("conversationId", input.conversationId());
        attrs.put("channel", input.channel());
        attrs.put("customerExternalId", input.customer().externalId());
        attrs.put("messagePreview", WorkflowRunContext.truncate(input.message(), 500));
        return attrs;
    }

    public static final class Stages {
        public static final String CONTEXT = BusinessContextAgent.AGENT_ID;
        public static final String REPLY = BusinessReplyAgent.AGENT_ID;

        private Stages() {}
    }
}
