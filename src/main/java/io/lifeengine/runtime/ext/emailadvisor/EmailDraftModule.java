package io.lifeengine.runtime.ext.emailadvisor;

import io.lifeengine.runtime.ext.emailadvisor.stages.EmailDraftAgent;
import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registra {@code email.draft.v1} (KAN-236).
 *
 * <p>Estrictamente aditivo, igual que {@link EmailTriageModule}: no toca ningún workflow existente.
 * Va en un módulo aparte del triage porque son dos capacidades independientes — un entorno puede
 * querer clasificar sin redactar, y de hecho ése es el estado por defecto de Business Chat.
 *
 * <p>Una sola etapa: redactar es una sola inferencia. Encadenar una etapa de revisión acá sería
 * meter criterio de negocio en el Runtime, y además duplicaría la latencia de lo único que una
 * persona está esperando en pantalla.
 *
 * <p>Dos minutos de timeout, como {@code business-chat.reply.v1} y a diferencia del minuto del
 * triage: esto genera prosa contra un modelo local, no una etiqueta.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.email-advisor.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class EmailDraftModule implements RuntimeModule {

    public static final String MODULE_ID = "email-advisor-draft";
    public static final String WORKFLOW_ID = "email.draft.v1";
    public static final String INPUT_CONTRACT = "email.draft-input.v1";
    public static final String OUTPUT_CONTRACT = "email.draft-output.v1";

    public static final String STAGE_DRAFT = "email-draft";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(RuntimeRegistry registry) {
        registry.registerWorkflow(
                new WorkflowDefinition(
                        WORKFLOW_ID,
                        INPUT_CONTRACT,
                        OUTPUT_CONTRACT,
                        List.of(WorkflowStage.agent(EmailDraftAgent.AGENT_ID, 1)),
                        Duration.ofMinutes(2),
                        "Redacta un borrador de respuesta a un correo: asunto y cuerpo, para que"
                                + " una persona lo apruebe. Prompt "
                                + EmailDraftPrompts.VERSION));
    }
}
