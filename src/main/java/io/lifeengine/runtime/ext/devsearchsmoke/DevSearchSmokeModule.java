package io.lifeengine.runtime.ext.devsearchsmoke;

import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.tools.search.SearchWebTool;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import io.lifeengine.runtime.workflow.WorkflowStage.StageKind;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Experimental smoke workflow for Capability Layer validation.
 *
 * <p>Registers {@code dev.search-smoke.v1}: a single {@code search.web} TOOL stage with no
 * LLM or agent. Used to verify that {@link SearchWebTool} integrates end-to-end inside the
 * {@link io.lifeengine.runtime.workflow.DefinitionDrivenWorkflowExecutor} TOOL path before
 * wiring search into production DevAgent workflows.
 *
 * <p>Requires both this module and the search tool to be enabled:
 * <pre>
 *   runtime.ext.dev-search-smoke.enabled=true
 *   runtime.tools.search.enabled=true
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "runtime.ext.dev-search-smoke.enabled", havingValue = "true")
public class DevSearchSmokeModule implements RuntimeModule {

    public static final String MODULE_ID = "dev-search-smoke";
    public static final String WORKFLOW_ID = "dev.search-smoke.v1";
    public static final String STAGE_SEARCH = "search-stage";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(RuntimeRegistry registry) {
        registry.registerWorkflow(
                new WorkflowDefinition(
                        WORKFLOW_ID,
                        "dev.search-smoke-input.v1",
                        "dev.search-smoke-output.v1",
                        List.of(new WorkflowStage(STAGE_SEARCH, 1, StageKind.TOOL, SearchWebTool.TOOL_ID)),
                        Duration.ofSeconds(30),
                        "Capability Layer smoke: single search.web TOOL stage (no LLM)"));
    }
}
