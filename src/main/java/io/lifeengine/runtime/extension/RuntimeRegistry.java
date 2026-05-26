package io.lifeengine.runtime.extension;

import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.agents.AgentRegistry;
import io.lifeengine.runtime.prompts.PromptTemplate;
import io.lifeengine.runtime.prompts.PromptTemplateRegistry;
import io.lifeengine.runtime.tools.ToolExecutor;
import io.lifeengine.runtime.tools.ToolRegistry;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowIds;
import io.lifeengine.runtime.workflow.WorkflowRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registration surface for {@link RuntimeModule} plug-ins (future verticals depend on runtime;
 * runtime never depends on verticals).
 */
@Component
public class RuntimeRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuntimeRegistry.class);

    private final WorkflowRegistry workflowRegistry;
    private final AgentRegistry agentRegistry;
    private final ToolRegistry toolRegistry;
    private final PromptTemplateRegistry promptTemplateRegistry;

    public RuntimeRegistry(
            WorkflowRegistry workflowRegistry,
            AgentRegistry agentRegistry,
            ToolRegistry toolRegistry,
            PromptTemplateRegistry promptTemplateRegistry) {
        this.workflowRegistry = workflowRegistry;
        this.agentRegistry = agentRegistry;
        this.toolRegistry = toolRegistry;
        this.promptTemplateRegistry = promptTemplateRegistry;
    }

    public void registerWorkflow(WorkflowDefinition definition) {
        if (WorkflowIds.DEMO_NO_LLM.equals(definition.workflowId())) {
            throw new IllegalArgumentException(
                    "Cannot register over built-in fake demo workflow: " + definition.workflowId());
        }
        workflowRegistry.register(definition);
        log.info("Registered workflow {}", definition.workflowId());
    }

    public void registerAgent(AgentExecutor agent) {
        if (agentRegistry.agents().stream().anyMatch(a -> a.agentId().equals(agent.agentId()))) {
            log.warn("Overwriting agent {}", agent.agentId());
        }
        agentRegistry.register(agent);
        log.info("Registered agent {}", agent.agentId());
    }

    public void registerTool(ToolExecutor tool) {
        if (toolRegistry.definitions().stream().anyMatch(d -> d.toolId().equals(tool.toolId()))) {
            log.warn("Overwriting tool {}", tool.toolId());
        }
        toolRegistry.register(tool);
        log.info("Registered tool {}", tool.toolId());
    }

    public void registerPromptTemplate(PromptTemplate template) {
        promptTemplateRegistry.register(template);
        log.info("Registered prompt template {}@{}", template.id(), template.version());
    }
}
