package io.lifeengine.runtime.ext.devcodereview;

import io.lifeengine.runtime.ext.devcodereview.stages.DevCodeReviewAgent;
import io.lifeengine.runtime.ext.devcodereview.stages.DevSummaryAgent;
import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registers the {@code dev.code-review.v1} workflow — a minimal two-agent pipeline that validates
 * a second runtime vertical without lifting the legacy dev-agent modulith module.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.dev-code-review.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DevCodeReviewModule implements RuntimeModule {

    public static final String MODULE_ID = "dev-code-review";
    public static final String WORKFLOW_ID = "dev.code-review.v1";
    public static final String INPUT_CONTRACT = "dev.code-review-input.v1";
    public static final String OUTPUT_CONTRACT = "dev.code-review-output.v1";

    public static final String STAGE_CODE_REVIEW = "code-review";
    public static final String STAGE_SUMMARY = "summary";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(RuntimeRegistry registry) {
        registry.registerPromptTemplate(DevCodeReviewPrompts.codeReview());
        registry.registerPromptTemplate(DevCodeReviewPrompts.summary());

        registry.registerWorkflow(
                new WorkflowDefinition(
                        WORKFLOW_ID,
                        INPUT_CONTRACT,
                        OUTPUT_CONTRACT,
                        List.of(
                                new WorkflowStage(
                                        STAGE_CODE_REVIEW,
                                        1,
                                        WorkflowStage.StageKind.AGENT,
                                        DevCodeReviewAgent.AGENT_ID),
                                new WorkflowStage(
                                        STAGE_SUMMARY,
                                        2,
                                        WorkflowStage.StageKind.AGENT,
                                        DevSummaryAgent.AGENT_ID)),
                        Duration.ofMinutes(2),
                        "Dev code review (code-review → summary)"));
    }
}
