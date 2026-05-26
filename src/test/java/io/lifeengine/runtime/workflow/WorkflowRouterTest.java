package io.lifeengine.runtime.workflow;

import io.lifeengine.runtime.core.UnknownWorkflowException;
import io.lifeengine.runtime.llm.LlmClient;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorkflowRouterTest {

    private WorkflowRegistry workflowRegistry;
    private DefinitionDrivenWorkflowExecutor definitionExecutor;
    private FakeWorkflowExecutor fakeExecutor;
    private WorkflowRouter router;

    @BeforeEach
    void setUp() {
        workflowRegistry = new WorkflowRegistry();
        definitionExecutor = Mockito.mock(DefinitionDrivenWorkflowExecutor.class);
        fakeExecutor = Mockito.mock(FakeWorkflowExecutor.class);
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        Mockito.when(llmClient.defaultModel()).thenReturn("test-model");
        router =
                new WorkflowRouter(workflowRegistry, definitionExecutor, fakeExecutor, llmClient);
    }

    @Test
    void start_unknownWorkflow_throws() {
        Assertions.assertThatThrownBy(
                        () -> router.start("example.vertical.workflow", UUID.randomUUID(), "in", "corr"))
                .isInstanceOf(UnknownWorkflowException.class);
    }

    @Test
    void start_registeredCustomWorkflow_usesDefinitionDrivenExecutor() {
        workflowRegistry.register(
                new WorkflowDefinition(
                        "example.vertical.workflow",
                        "runtime.text.v1",
                        "runtime.text.v1",
                        List.of(WorkflowStage.tool("demo.echo", 1)),
                        null,
                        "Vertical test workflow"));

        UUID runId = UUID.randomUUID();
        String label = router.start("example.vertical.workflow", runId, "hello", "corr-1");

        Assertions.assertThat(label).isEqualTo("definition");
        Mockito.verify(definitionExecutor)
                .schedule(
                        runId,
                        workflowRegistry.require("example.vertical.workflow"),
                        "hello",
                        "corr-1");
        Mockito.verifyNoInteractions(fakeExecutor);
    }

    @Test
    void start_demoLlm_returnsLlmExecutorLabel() {
        workflowRegistry.register(
                WorkflowDefinition.llmDemo(
                        WorkflowIds.DEMO_LLM, List.of(WorkflowStage.agent("summarizer-agent", 1))));

        String label = router.start(WorkflowIds.DEMO_LLM, UUID.randomUUID(), "in", "corr");

        Assertions.assertThat(label).isEqualTo("llm");
    }

    @Test
    void requestCancel_customWorkflow_delegatesToDefinitionExecutor() {
        workflowRegistry.register(
                WorkflowDefinition.llmDemo(
                        WorkflowIds.DEMO_LLM, List.of(WorkflowStage.agent("summarizer-agent", 1))));
        UUID runId = UUID.randomUUID();
        Mockito.when(definitionExecutor.requestCancel(runId)).thenReturn(true);

        boolean signalled = router.requestCancel(WorkflowIds.DEMO_LLM, runId);

        Assertions.assertThat(signalled).isTrue();
        Mockito.verify(definitionExecutor).requestCancel(runId);
    }

    @Test
    void requestCancel_unknownWorkflow_returnsFalse() {
        boolean signalled = router.requestCancel("missing.workflow", UUID.randomUUID());
        Assertions.assertThat(signalled).isFalse();
    }
}
