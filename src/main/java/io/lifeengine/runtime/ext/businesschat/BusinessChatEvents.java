package io.lifeengine.runtime.ext.businesschat;

import io.lifeengine.runtime.agents.StrictAgentJson;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.businesschat.stages.BusinessContextAgent;
import io.lifeengine.runtime.ext.businesschat.stages.BusinessReplyAgent;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.LinkedHashMap;
import java.util.Map;

/** Domain events for the business-chat workflow on the runtime event spine. */
public final class BusinessChatEvents {

    private BusinessChatEvents() {}

    public static void emitStarted(
            WorkflowRunContext ctx, String stageId, BusinessChatReplyIo.Input input) {
        ctx.emit(
                EventType.BUSINESS_CHAT_STARTED,
                baseAttrs(stageId, input),
                false);
    }

    public static void emitHandoff(
            WorkflowRunContext ctx,
            String stageId,
            BusinessChatReplyIo.Input input,
            BusinessHandoffService.HandoffDecision handoff,
            String intent,
            String confidence) {
        if (!handoff.handoffRequired()) {
            return;
        }
        Map<String, String> attrs = baseAttrs(stageId, input);
        attrs.put("intent", intent);
        attrs.put("confidence", confidence);
        attrs.put("handoffRequired", "true");
        if (handoff.reason() != null) {
            attrs.put("handoffReason", handoff.reason().name());
        }
        ctx.emit(EventType.BUSINESS_CHAT_HANDOFF, attrs, false);
    }

    public static void emitResponded(
            WorkflowRunContext ctx,
            String stageId,
            BusinessChatReplyIo.Input input,
            StrictAgentJson.BusinessReplyOutput reply,
            boolean handoffRequired) {
        Map<String, String> attrs = baseAttrs(stageId, input);
        attrs.put("intent", reply.intent());
        attrs.put("confidence", reply.confidence());
        attrs.put("handoffRequired", Boolean.toString(handoffRequired));
        attrs.put("leadCaptured", Boolean.toString(reply.leadCaptured()));
        attrs.put("responsePreview", WorkflowRunContext.truncate(reply.response(), 500));
        ctx.emit(EventType.BUSINESS_CHAT_RESPONDED, attrs, false);
    }

    public static BusinessChatReplyIo.Input parseInput(
            com.fasterxml.jackson.databind.ObjectMapper mapper, String raw) throws Exception {
        return BusinessChatReplyIo.readInput(mapper, raw);
    }

    private static Map<String, String> baseAttrs(String stageId, BusinessChatReplyIo.Input input) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("stageId", stageId);
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
