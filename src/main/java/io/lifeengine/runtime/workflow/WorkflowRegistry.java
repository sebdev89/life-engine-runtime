package io.lifeengine.runtime.workflow;

import io.lifeengine.runtime.core.UnknownWorkflowException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    private final Map<String, WorkflowDefinition> definitions = new LinkedHashMap<>();

    public void register(WorkflowDefinition definition) {
        WorkflowDefinition previous = definitions.put(definition.workflowId(), definition);
        if (previous != null) {
            log.warn("Overwriting workflow definition for {}", definition.workflowId());
        }
    }

    public WorkflowDefinition require(String workflowId) {
        WorkflowDefinition definition = definitions.get(workflowId);
        if (definition == null) {
            throw new UnknownWorkflowException(workflowId);
        }
        return definition;
    }

    public Collection<WorkflowDefinition> definitions() {
        return List.copyOf(definitions.values());
    }
}
