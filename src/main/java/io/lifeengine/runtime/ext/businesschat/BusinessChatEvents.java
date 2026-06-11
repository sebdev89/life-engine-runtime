package io.lifeengine.runtime.ext.businesschat;

import io.lifeengine.runtime.agents.StrictAgentJson;
import io.lifeengine.runtime.ext.businesschat.stages.BusinessContextAgent;
import io.lifeengine.runtime.ext.businesschat.stages.BusinessReplyAgent;
import io.lifeengine.runtime.workflow.WorkflowRunContext;

/**
 * @deprecated Use {@link BusinessChatObservabilityEvents} directly. Kept for gradual migration.
 */
@Deprecated
public final class BusinessChatEvents {

    private BusinessChatEvents() {}

    public static void emitStarted(
            WorkflowRunContext ctx, String stageId, BusinessChatReplyIo.Input input) {
        BusinessChatObservabilityEvents.emitConversationStarted(ctx, stageId, input);
    }

    public static void emitHandoff(
            WorkflowRunContext ctx,
            String stageId,
            BusinessChatReplyIo.Input input,
            BusinessHandoffService.HandoffDecision handoff,
            String intent,
            String confidence) {
        // Handoff is authoritative in business-chat-service; runtime no longer emits HANDOFF_DECISION.
    }

    public static void emitResponded(
            WorkflowRunContext ctx,
            String stageId,
            BusinessChatReplyIo.Input input,
            StrictAgentJson.BusinessReplyOutput reply,
            boolean handoffRequired) {
        BusinessChatObservabilityEvents.emitResponseGenerated(ctx, stageId, input, reply, handoffRequired);
    }

    public static BusinessChatReplyIo.Input parseInput(
            com.fasterxml.jackson.databind.ObjectMapper mapper, String raw) throws Exception {
        return BusinessChatObservabilityEvents.parseInput(mapper, raw);
    }

    public static final class Stages {
        public static final String CONTEXT = BusinessContextAgent.AGENT_ID;
        public static final String REPLY = BusinessReplyAgent.AGENT_ID;

        private Stages() {}
    }
}
