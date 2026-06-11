package io.lifeengine.runtime.ext.businesschat;

import io.lifeengine.runtime.ext.businesschat.prompts.BusinessPromptRegistry;
import io.lifeengine.runtime.ext.businesschat.stages.BusinessContextAgent;
import io.lifeengine.runtime.ext.businesschat.stages.BusinessReplyAgent;
import io.lifeengine.runtime.ext.businesschat.stages.LeadCaptureAgent;
import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registers the {@code business-chat.reply.v1} workflow — a business customer-service pipeline
 * that normalizes multi-channel inbound messages through the runtime spine.
 *
 * <p>Lead capture is optional ({@code lifeengine.runtime.ext.business-chat.lead-capture.enabled});
 * when disabled the workflow keeps the original two-stage shape (context → reply).
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BusinessChatReplyModule implements RuntimeModule {

    public static final String MODULE_ID = "business-chat";
    public static final String WORKFLOW_ID = "business-chat.reply.v1";
    public static final String INPUT_CONTRACT = "business-chat.reply-input.v1";
    public static final String OUTPUT_CONTRACT = "business-chat.reply-output.v1";

    public static final String STAGE_BUSINESS_CONTEXT = "business-context";
    public static final String STAGE_LEAD_CAPTURE = "lead-capture";
    public static final String STAGE_BUSINESS_REPLY = "business-reply";

    private final boolean leadCaptureEnabled;
    private final BusinessPromptRegistry businessPromptRegistry;

    public BusinessChatReplyModule(
            @Value("${lifeengine.runtime.ext.business-chat.lead-capture.enabled:false}")
                    boolean leadCaptureEnabled,
            BusinessPromptRegistry businessPromptRegistry) {
        this.leadCaptureEnabled = leadCaptureEnabled;
        this.businessPromptRegistry = businessPromptRegistry;
    }

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(RuntimeRegistry registry) {
        for (var template : businessPromptRegistry.activeLlmTemplates()) {
            registry.registerPromptTemplate(template);
        }
        if (leadCaptureEnabled
                && businessPromptRegistry.activeLlmTemplates().stream()
                        .noneMatch(
                                template ->
                                        BusinessChatReplyPrompts.LEAD_CAPTURE_ID.equals(template.id()))) {
            registry.registerPromptTemplate(BusinessChatReplyPrompts.leadCapture());
        }

        registry.registerWorkflow(
                new WorkflowDefinition(
                        WORKFLOW_ID,
                        INPUT_CONTRACT,
                        OUTPUT_CONTRACT,
                        buildStages(),
                        Duration.ofMinutes(2),
                        workflowDescription()));
    }

    private List<WorkflowStage> buildStages() {
        List<WorkflowStage> stages = new ArrayList<>();
        stages.add(
                new WorkflowStage(
                        STAGE_BUSINESS_CONTEXT,
                        1,
                        WorkflowStage.StageKind.AGENT,
                        BusinessContextAgent.AGENT_ID));
        if (leadCaptureEnabled) {
            stages.add(
                    new WorkflowStage(
                            STAGE_LEAD_CAPTURE,
                            2,
                            WorkflowStage.StageKind.AGENT,
                            LeadCaptureAgent.AGENT_ID));
            stages.add(
                    new WorkflowStage(
                            STAGE_BUSINESS_REPLY,
                            3,
                            WorkflowStage.StageKind.AGENT,
                            BusinessReplyAgent.AGENT_ID));
        } else {
            stages.add(
                    new WorkflowStage(
                            STAGE_BUSINESS_REPLY,
                            2,
                            WorkflowStage.StageKind.AGENT,
                            BusinessReplyAgent.AGENT_ID));
        }
        return List.copyOf(stages);
    }

    private String workflowDescription() {
        if (leadCaptureEnabled) {
            return "Business chat reply (business-context → lead-capture → business-reply)";
        }
        return "Business chat reply (business-context → business-reply)";
    }
}
