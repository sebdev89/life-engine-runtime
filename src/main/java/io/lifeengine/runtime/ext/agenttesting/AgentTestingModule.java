package io.lifeengine.runtime.ext.agenttesting;

import io.lifeengine.runtime.ext.agenttesting.stages.EvaluateTranscriptAgent;
import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Registers {@code agent-testing.evaluate.v1} for ATP Lite transcript scoring. */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.agent-testing.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AgentTestingModule implements RuntimeModule {

    public static final String MODULE_ID = "agent-testing";
    public static final String EVALUATE_WORKFLOW_ID = "agent-testing.evaluate.v1";
    public static final String STAGE_EVALUATE = "evaluate-transcript";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(RuntimeRegistry registry) {
        registry.registerWorkflow(
                new WorkflowDefinition(
                        EVALUATE_WORKFLOW_ID,
                        "agent-testing.evaluate-input.v1",
                        "agent-testing.evaluate-output.v1",
                        List.of(
                                new WorkflowStage(
                                        STAGE_EVALUATE,
                                        1,
                                        WorkflowStage.StageKind.AGENT,
                                        EvaluateTranscriptAgent.AGENT_ID)),
                        Duration.ofMinutes(2),
                        "ATP evaluate transcript (deterministic passthrough)"));
    }
}
