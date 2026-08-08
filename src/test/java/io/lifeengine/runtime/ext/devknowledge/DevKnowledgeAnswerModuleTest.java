package io.lifeengine.runtime.ext.devknowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.runtime.ext.devknowledge.stages.DevKnowledgeAnswerAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fija los identificadores que Dev Agent tiene cableados del otro lado (KAN-243).
 *
 * <p>Son tests de una línea y valen lo que valen los tres bugs que evitan. El defecto original de
 * {@code /ask} fue exactamente de esta clase: Dev Agent pedía {@code dev.knowledge-answer.v1} y en el
 * Runtime no había nada con ese nombre. El síntoma llegó como un 503 opaco, a tres servicios de
 * distancia de la causa.
 */
class DevKnowledgeAnswerModuleTest {

    @Test
    @DisplayName("el workflowId es el que Dev Agent pide en dev-agent.runtime.workflow-id")
    void workflowIdMatchesDevAgentConfiguration() {
        assertThat(DevKnowledgeAnswerModule.WORKFLOW_ID).isEqualTo("dev.knowledge-answer.v1");
    }

    @Test
    @DisplayName("el stageId es el que DevKnowledgeAnswerParser busca para extraer la respuesta")
    void stageIdMatchesDevAgentParser() {
        // DevKnowledgeAnswerParser.STAGE_DEV_ANSWER. Si cambia, /ask falla con "Missing dev-answer
        // stage output" y nada apunta a este módulo.
        assertThat(DevKnowledgeAnswerModule.STAGE_DEV_ANSWER).isEqualTo("dev-answer");
    }

    @Test
    @DisplayName("el módulo registra el workflow con su única etapa y el agente correcto")
    void registersWorkflowWithSingleStage() {
        // Se usa el RuntimeRegistry real, no un doble: el registro pasa por sus validaciones
        // (nombres reservados, duplicados), que es parte de lo que hay que comprobar.
        var workflows = new io.lifeengine.runtime.workflow.WorkflowRegistry();
        var prompts = new io.lifeengine.runtime.prompts.PromptTemplateRegistry();
        var registry =
                new io.lifeengine.runtime.extension.RuntimeRegistry(
                        workflows,
                        new io.lifeengine.runtime.agents.AgentRegistry(java.util.List.of()),
                        new io.lifeengine.runtime.tools.ToolRegistry(java.util.List.of()),
                        prompts);

        var module = new DevKnowledgeAnswerModule();
        assertThat(module.moduleId()).isEqualTo("dev-knowledge");
        module.register(registry);

        var definition = workflows.require(DevKnowledgeAnswerModule.WORKFLOW_ID);
        assertThat(definition.stages()).hasSize(1);
        assertThat(definition.stages().get(0).stageId()).isEqualTo("dev-answer");
        assertThat(definition.stages().get(0).refId()).isEqualTo(DevKnowledgeAnswerAgent.AGENT_ID);

        // El prompt tiene que quedar registrado o el agente falla al pedirlo con require().
        assertThat(prompts.require(DevKnowledgePrompts.ANSWER_ID, DevKnowledgePrompts.VERSION_V1))
                .isNotNull();
    }
}
