package io.lifeengine.runtime.api;

import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowRegistry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/runtime/workflows")
public class RuntimeWorkflowsController {

    private final WorkflowRegistry workflowRegistry;

    public RuntimeWorkflowsController(WorkflowRegistry workflowRegistry) {
        this.workflowRegistry = workflowRegistry;
    }

    @GetMapping
    public Mono<List<WorkflowListView>> listWorkflows() {
        return Mono.fromCallable(
                () ->
                        workflowRegistry.definitions().stream()
                                .map(RuntimeWorkflowsController::toView)
                                .toList());
    }

    private static WorkflowListView toView(WorkflowDefinition definition) {
        return new WorkflowListView(
                definition.workflowId(),
                definition.description(),
                definition.inputContract(),
                definition.outputContract());
    }
}
