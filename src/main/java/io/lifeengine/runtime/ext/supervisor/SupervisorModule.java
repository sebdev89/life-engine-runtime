package io.lifeengine.runtime.ext.supervisor;

import io.lifeengine.runtime.ext.supervisor.stages.SupervisorRouteAgent;
import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Registers {@code supervisor.route.v1} — policy-driven workflow selection for BC ingress. */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.supervisor.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SupervisorModule implements RuntimeModule {

    public static final String MODULE_ID = "supervisor";
    public static final String WORKFLOW_ID = "supervisor.route.v1";
    public static final String INPUT_CONTRACT = "supervisor.route-input.v1";
    public static final String OUTPUT_CONTRACT = "supervisor.route-output.v1";
    public static final String STAGE_ROUTE = "supervisor-route";

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
                                        STAGE_ROUTE,
                                        1,
                                        WorkflowStage.StageKind.AGENT,
                                        SupervisorRouteAgent.AGENT_ID)),
                        Duration.ofSeconds(30),
                        "Supervisor route (tenant/channel/intent → workflowId)"));
    }
}
