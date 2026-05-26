package io.lifeengine.runtime.workflow;

import io.lifeengine.runtime.core.UnknownWorkflowException;
import io.lifeengine.runtime.llm.LlmClient;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Routes workflowId to registered definition + executor — no silent fallback. */
@Component
public class WorkflowRouter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRouter.class);

    private final WorkflowRegistry workflowRegistry;
    private final DefinitionDrivenWorkflowExecutor definitionDrivenWorkflowExecutor;
    private final FakeWorkflowExecutor fakeWorkflowExecutor;
    private final LlmClient llmClient;

    public WorkflowRouter(
            WorkflowRegistry workflowRegistry,
            DefinitionDrivenWorkflowExecutor definitionDrivenWorkflowExecutor,
            FakeWorkflowExecutor fakeWorkflowExecutor,
            LlmClient llmClient) {
        this.workflowRegistry = workflowRegistry;
        this.definitionDrivenWorkflowExecutor = definitionDrivenWorkflowExecutor;
        this.fakeWorkflowExecutor = fakeWorkflowExecutor;
        this.llmClient = llmClient;
    }

    /**
     * @return executor label stored on run metadata ({@code fake}, {@code llm}, or {@code definition})
     */
    public String start(String workflowId, UUID runId, String input, String correlationId) {
        log.info("Starting workflow {} runId={} correlationId={}", workflowId, runId, correlationId);

        if (WorkflowIds.DEMO_NO_LLM.equals(workflowId)) {
            workflowRegistry.require(workflowId);
            log.info("Routing runId={} to FakeWorkflowExecutor (explicit demo, no LLM)", runId);
            fakeWorkflowExecutor.schedule(runId, correlationId);
            return "fake";
        }

        WorkflowDefinition definition = workflowRegistry.require(workflowId);
        log.info(
                "Routing runId={} to definition-driven executor workflowId={} model={}",
                runId,
                workflowId,
                llmClient.defaultModel());
        definitionDrivenWorkflowExecutor.schedule(runId, definition, input, correlationId);
        if (WorkflowIds.DEMO_LLM.equals(workflowId)) {
            return "llm";
        }
        return "definition";
    }

    public boolean requestCancel(String workflowId, UUID runId) {
        if (WorkflowIds.DEMO_NO_LLM.equals(workflowId)) {
            return fakeWorkflowExecutor.requestCancel(runId);
        }
        try {
            workflowRegistry.require(workflowId);
        } catch (UnknownWorkflowException ex) {
            return false;
        }
        return definitionDrivenWorkflowExecutor.requestCancel(runId);
    }
}
