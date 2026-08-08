package io.lifeengine.runtime.ext.devknowledge;

import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.ext.devknowledge.stages.DevKnowledgeAnswerAgent;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registra {@code dev.knowledge-answer.v1} — el workflow que responde preguntas sobre una base de
 * código con los fragmentos que Dev Agent recupera de RAG.
 *
 * <h2>Por qué existe este archivo</h2>
 *
 * <p>Dev Agent llamaba a este workflow desde que existe su endpoint {@code POST /ask}, y el Runtime
 * nunca lo tuvo registrado: contestaba {@code 400 unknown_workflow} y Dev Agent lo traducía a un 503.
 * O sea que {@code /ask} <b>nunca funcionó</b> — no era una falla de configuración ni de red, era una
 * pieza que no estaba escrita. Las métricas de {@code dev_agent_ask_duration} en UAT estaban en cero
 * desde el primer día, que era la señal y pasó inadvertida.
 *
 * <h2>El identificador de la etapa es un contrato</h2>
 *
 * <p>{@code STAGE_DEV_ANSWER} tiene que ser exactamente {@code dev-answer}: es lo que
 * {@code DevKnowledgeAnswerParser} busca del lado de Dev Agent para encontrar la respuesta. Si se
 * renombra acá, {@code /ask} vuelve a fallar — y falla con "Missing dev-answer stage output", que no
 * apunta a este archivo. Hay un test que lo fija.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.dev-knowledge.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DevKnowledgeAnswerModule implements RuntimeModule {

    public static final String MODULE_ID = "dev-knowledge";
    public static final String WORKFLOW_ID = "dev.knowledge-answer.v1";
    public static final String INPUT_CONTRACT = "dev.knowledge-answer-input.v1";
    public static final String OUTPUT_CONTRACT = "dev.knowledge-answer-output.v1";

    /** Lo consume {@code DevKnowledgeAnswerParser.STAGE_DEV_ANSWER} en Dev Agent. No renombrar. */
    public static final String STAGE_DEV_ANSWER = "dev-answer";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(RuntimeRegistry registry) {
        registry.registerPromptTemplate(DevKnowledgePrompts.answer());
        registry.registerWorkflow(
                new WorkflowDefinition(
                        WORKFLOW_ID,
                        INPUT_CONTRACT,
                        OUTPUT_CONTRACT,
                        List.of(
                                new WorkflowStage(
                                        STAGE_DEV_ANSWER,
                                        1,
                                        WorkflowStage.StageKind.AGENT,
                                        DevKnowledgeAnswerAgent.AGENT_ID)),
                        // Una sola llamada al LLM, pero sobre fragmentos de código que pueden ser
                        // largos y contra un modelo local. El timeout de etapa es lo único que evita
                        // que un run quede en RUNNING para siempre si el proveedor no responde.
                        Duration.ofMinutes(3),
                        "Responde preguntas sobre una base de código usando fragmentos de RAG"));
    }
}
