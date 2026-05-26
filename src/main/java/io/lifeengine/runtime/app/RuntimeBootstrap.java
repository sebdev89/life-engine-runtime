package io.lifeengine.runtime.app;

import io.lifeengine.runtime.agents.ClassifierAgent;
import io.lifeengine.runtime.agents.SummarizerAgent;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowIds;
import io.lifeengine.runtime.workflow.WorkflowRegistry;
import io.lifeengine.runtime.workflow.WorkflowStage;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.stereotype.Component;

/** Registers built-in demo workflows (generic runtime MLP — no vertical modules). */
@Component
public class RuntimeBootstrap {

    private final WorkflowRegistry workflowRegistry;

    public RuntimeBootstrap(WorkflowRegistry workflowRegistry) {
        this.workflowRegistry = workflowRegistry;
    }

    @PostConstruct
    void registerDemoWorkflows() {
        workflowRegistry.register(
                WorkflowDefinition.llmDemo(
                        WorkflowIds.DEMO_LLM,
                        List.of(
                                WorkflowStage.agent(SummarizerAgent.AGENT_ID, 1),
                                WorkflowStage.agent(ClassifierAgent.AGENT_ID, 2))));

        workflowRegistry.register(
                new WorkflowDefinition(
                        WorkflowIds.DEMO_NO_LLM,
                        "runtime.none.v1",
                        "runtime.none.v1",
                        List.of(),
                        null,
                        "Explicit fake demo pipeline (no LLM, no agents from registry)"));
    }
}
