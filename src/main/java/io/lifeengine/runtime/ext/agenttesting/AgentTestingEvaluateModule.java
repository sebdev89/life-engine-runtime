package io.lifeengine.runtime.ext.agenttesting;

import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.ext.agenttesting.stages.AgentTestingEvaluateAgent;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Registers {@code agent-testing.evaluate.v1} for ATP scenario evaluation. */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.agent-testing.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AgentTestingEvaluateModule implements RuntimeModule {

    public static final String MODULE_ID = "agent-testing";
    public static final String WORKFLOW_ID = "agent-testing.evaluate.v1";
    public static final String INPUT_CONTRACT = "agent-testing.evaluate-input.v1";
    public static final String OUTPUT_CONTRACT = "agent-testing.evaluate-output.v1";
    public static final String STAGE_EVALUATE = "evaluate-transcript";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(RuntimeRegistry registry) {
        registry.registerWorkflow(
                new WorkflowDefinition(
                        WORKFLOW_ID,
                        INPUT_CONTRACT,
                        OUTPUT_CONTRACT,
                        List.of(
                                new WorkflowStage(
                                        STAGE_EVALUATE,
                                        1,
                                        WorkflowStage.StageKind.AGENT,
                                        AgentTestingEvaluateAgent.AGENT_ID)),
                        Duration.ofMinutes(1),
                        "ATP deterministic transcript evaluation (no LLM)"));
    }
}
