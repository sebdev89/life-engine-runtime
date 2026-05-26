package io.lifeengine.runtime.workflow;

import io.lifeengine.runtime.core.UnknownWorkflowException;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class WorkflowRegistryTest {

    @Test
    void require_unknownWorkflow_throws() {
        WorkflowRegistry registry = new WorkflowRegistry();
        Assertions.assertThatThrownBy(() -> registry.require("example.vertical.workflow"))
                .isInstanceOf(UnknownWorkflowException.class);
    }

    @Test
    void register_overwrite_keepsLatestDefinition() {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(
                WorkflowDefinition.llmDemo(
                        "example.vertical.workflow",
                        List.of(WorkflowStage.agent("summarizer-agent", 1))));
        registry.register(
                WorkflowDefinition.llmDemo(
                        "example.vertical.workflow",
                        List.of(WorkflowStage.agent("classifier-agent", 1))));
        WorkflowDefinition definition = registry.require("example.vertical.workflow");
        Assertions.assertThat(definition.stages()).hasSize(1);
        Assertions.assertThat(definition.stages().getFirst().refId()).isEqualTo("classifier-agent");
    }

    @Test
    void register_andResolve_demoLlm() {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.register(
                WorkflowDefinition.llmDemo(
                        WorkflowIds.DEMO_LLM,
                        List.of(WorkflowStage.agent("summarizer-agent", 1))));
        WorkflowDefinition definition = registry.require(WorkflowIds.DEMO_LLM);
        Assertions.assertThat(definition.stages()).hasSize(1);
        Assertions.assertThat(definition.inputContract()).isEqualTo("runtime.text.v1");
    }
}
