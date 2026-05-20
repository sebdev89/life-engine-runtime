package io.lifeengine.runtime.workflow;

import io.lifeengine.runtime.core.FakeWorkflowExecutor;
import io.lifeengine.runtime.core.UnknownWorkflowException;
import io.lifeengine.runtime.llm.OpenAiCompatibleLlmClient;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Strict workflow dispatch — no silent fallback between LLM and fake pipelines. */
@Component
public class WorkflowRouter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRouter.class);

    private final FakeWorkflowExecutor fakeWorkflowExecutor;
    private final WorkflowExecutor workflowExecutor;
    private final OpenAiCompatibleLlmClient llmClient;

    public WorkflowRouter(
            FakeWorkflowExecutor fakeWorkflowExecutor,
            WorkflowExecutor workflowExecutor,
            OpenAiCompatibleLlmClient llmClient) {
        this.fakeWorkflowExecutor = fakeWorkflowExecutor;
        this.workflowExecutor = workflowExecutor;
        this.llmClient = llmClient;
    }

    /**
     * @return executor label stored on the run ({@code llm} or {@code fake})
     */
    public String start(String workflowId, UUID runId, String input) {
        log.info("Starting workflow {}", workflowId);

        if (WorkflowRunContext.LLM_WORKFLOW_ID.equals(workflowId)) {
            log.info(
                    "Routing runId={} to LLM WorkflowExecutor (summarizer-agent → classifier-agent), model={}",
                    runId,
                    llmClient.defaultModel());
            workflowExecutor.schedule(runId, input);
            return "llm";
        }

        if (WorkflowRunContext.NO_LLM_WORKFLOW_ID.equals(workflowId)) {
            log.info("Routing runId={} to FakeWorkflowExecutor (agent-a / agent-b)", runId);
            fakeWorkflowExecutor.schedule(runId);
            return "fake";
        }

        log.warn("Rejected unknown workflowId={} for runId={}", workflowId, runId);
        throw new UnknownWorkflowException(workflowId);
    }

    public boolean requestCancel(String workflowId, UUID runId) {
        if (WorkflowRunContext.NO_LLM_WORKFLOW_ID.equals(workflowId)) {
            return fakeWorkflowExecutor.requestCancel(runId);
        }
        if (WorkflowRunContext.LLM_WORKFLOW_ID.equals(workflowId)) {
            return workflowExecutor.requestCancel(runId);
        }
        return false;
    }
}
