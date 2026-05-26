package io.lifeengine.runtime.extension;

import io.lifeengine.runtime.agents.AgentRegistry;
import io.lifeengine.runtime.prompts.PromptTemplateRegistry;
import io.lifeengine.runtime.tools.ToolRegistry;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowIds;
import io.lifeengine.runtime.workflow.WorkflowRegistry;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class RuntimeRegistryTest {

    @Test
    void registerWorkflow_cannotReplaceFakeDemoWorkflow() {
        RuntimeRegistry registry =
                new RuntimeRegistry(
                        new WorkflowRegistry(),
                        new AgentRegistry(List.of()),
                        new ToolRegistry(List.of()),
                        new PromptTemplateRegistry());

        Assertions.assertThatThrownBy(
                        () ->
                                registry.registerWorkflow(
                                        new WorkflowDefinition(
                                                WorkflowIds.DEMO_NO_LLM,
                                                "x",
                                                "x",
                                                List.of(),
                                                null,
                                                "blocked")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(WorkflowIds.DEMO_NO_LLM);
    }
}
