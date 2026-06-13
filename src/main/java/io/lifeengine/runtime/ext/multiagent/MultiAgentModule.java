package io.lifeengine.runtime.ext.multiagent;

import io.lifeengine.runtime.ext.multiagent.stages.MultiAgentDelegateAgent;
import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Registers {@code multi-agent.delegate.v1} — specialist selection for BC ingress. */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.multi-agent.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MultiAgentModule implements RuntimeModule {

    public static final String MODULE_ID = "multi-agent";
    public static final String WORKFLOW_ID = "multi-agent.delegate.v1";
    public static final String INPUT_CONTRACT = "multi-agent.delegate-input.v1";
    public static final String OUTPUT_CONTRACT = "multi-agent.delegate-output.v1";
    public static final String STAGE_DELEGATE = "multi-agent-delegate";

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
                                        STAGE_DELEGATE,
                                        1,
                                        WorkflowStage.StageKind.AGENT,
                                        MultiAgentDelegateAgent.AGENT_ID)),
                        Duration.ofSeconds(30),
                        "Multi-agent delegate (tenant/intent → specialist workflow)"));
    }
}
