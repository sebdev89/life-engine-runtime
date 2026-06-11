package io.lifeengine.runtime.ext.devknowledgeanswer;

import io.lifeengine.runtime.ext.devknowledgeanswer.stages.DevAnswerAgent;
import io.lifeengine.runtime.ext.devknowledgeanswer.stages.DevContextAgent;
import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registers the {@code dev.knowledge-answer.v1} workflow — RAG-grounded developer Q&amp;A for the
 * Dev Agent vertical.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.dev-knowledge-answer.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DevKnowledgeAnswerModule implements RuntimeModule {

    public static final String MODULE_ID = "dev-knowledge-answer";
    public static final String WORKFLOW_ID = "dev.knowledge-answer.v1";
    public static final String INPUT_CONTRACT = "dev.knowledge-answer-input.v1";
    public static final String OUTPUT_CONTRACT = "dev.knowledge-answer-output.v1";

    public static final String STAGE_CONTEXT = "dev-context";
    public static final String STAGE_ANSWER = "dev-answer";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(RuntimeRegistry registry) {
        registry.registerPromptTemplate(DevKnowledgeAnswerPrompts.answer());

        registry.registerWorkflow(
                new WorkflowDefinition(
                        WORKFLOW_ID,
                        INPUT_CONTRACT,
                        OUTPUT_CONTRACT,
                        List.of(
                                new WorkflowStage(
                                        STAGE_CONTEXT,
                                        1,
                                        WorkflowStage.StageKind.AGENT,
                                        DevContextAgent.AGENT_ID),
                                new WorkflowStage(
                                        STAGE_ANSWER,
                                        2,
                                        WorkflowStage.StageKind.AGENT,
                                        DevAnswerAgent.AGENT_ID)),
                        Duration.ofMinutes(2),
                        "Dev knowledge answer (dev-context → dev-answer)"));
    }
}
