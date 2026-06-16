package io.lifeengine.runtime.ext.devknowledgeanswer;

import io.lifeengine.runtime.ext.devknowledgeanswer.stages.DevAnswerAgent;
import io.lifeengine.runtime.ext.devknowledgeanswer.stages.DevContextAgent;
import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.tools.rag.RagQueryTool;
import io.lifeengine.runtime.tools.search.SearchWebTool;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registers the {@code dev.knowledge-answer.v1} workflow — RAG-grounded developer Q&amp;A for the
 * Dev Agent vertical.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.dev-knowledge-answer.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DevKnowledgeAnswerModule implements RuntimeModule {

    public static final String MODULE_ID = "dev-knowledge-answer";
    public static final String WORKFLOW_ID = "dev.knowledge-answer.v1";
    public static final String INPUT_CONTRACT = "dev.knowledge-answer-input.v1";
    public static final String OUTPUT_CONTRACT = "dev.knowledge-answer-output.v1";

    public static final String STAGE_SEARCH = "search-web";
    public static final String STAGE_RAG    = "rag-query";
    public static final String STAGE_CONTEXT = "dev-context";
    public static final String STAGE_ANSWER  = "dev-answer";

    private final boolean ragEnabled;
    private final boolean searchEnabled;

    public DevKnowledgeAnswerModule(
            @Value("${runtime.tools.rag.enabled:false}") boolean ragEnabled,
            @Value("${runtime.tools.search.enabled:false}") boolean searchEnabled) {
        this.ragEnabled = ragEnabled;
        this.searchEnabled = searchEnabled;
    }

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(RuntimeRegistry registry) {
        registry.registerPromptTemplate(DevKnowledgeAnswerPrompts.answer());

        List<WorkflowStage> stages = new ArrayList<>();
        int order = 1;
        if (searchEnabled) {
            stages.add(new WorkflowStage(STAGE_SEARCH, order++, WorkflowStage.StageKind.TOOL, SearchWebTool.TOOL_ID));
        }
        if (ragEnabled) {
            stages.add(new WorkflowStage(STAGE_RAG, order++, WorkflowStage.StageKind.TOOL, RagQueryTool.TOOL_ID));
        }
        stages.add(new WorkflowStage(STAGE_CONTEXT, order++, WorkflowStage.StageKind.AGENT, DevContextAgent.AGENT_ID));
        stages.add(new WorkflowStage(STAGE_ANSWER,  order,   WorkflowStage.StageKind.AGENT, DevAnswerAgent.AGENT_ID));

        StringBuilder desc = new StringBuilder("Dev knowledge answer (");
        if (searchEnabled) desc.append("search-web → ");
        if (ragEnabled)    desc.append("rag-query → ");
        desc.append("dev-context → dev-answer)");

        registry.registerWorkflow(
                new WorkflowDefinition(
                        WORKFLOW_ID,
                        INPUT_CONTRACT,
                        OUTPUT_CONTRACT,
                        List.copyOf(stages),
                        Duration.ofMinutes(2),
                        desc.toString()));
    }
}
