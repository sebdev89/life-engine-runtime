package io.lifeengine.runtime.ext.emailadvisor;

import io.lifeengine.runtime.ext.emailadvisor.stages.EmailTriageAgent;
import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registra {@code email.triage.v1} (KAN-234).
 *
 * <p>Estrictamente aditivo: no toca {@code business-chat.reply.v1} ni ningún otro workflow. Una sola
 * etapa, porque clasificar es una sola inferencia — encadenar etapas acá sólo agregaría latencia y
 * puntos de falla sin agregar información.
 *
 * <p>Reparto de responsabilidades, que es lo que sostiene este diseño: <b>Business Chat</b> sabe qué
 * significa HIGH, qué es JOB_OPPORTUNITY y cuándo un correo necesita respuesta. <b>Runtime</b>
 * ejecuta la inferencia. Si la semántica del correo se filtrara acá, agregar una categoría pasaría a
 * ser un cambio en dos repositorios y un despliegue coordinado.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.email-advisor.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class EmailTriageModule implements RuntimeModule {

    public static final String MODULE_ID = "email-advisor";
    public static final String WORKFLOW_ID = "email.triage.v1";
    public static final String INPUT_CONTRACT = "email.triage-input.v1";
    public static final String OUTPUT_CONTRACT = "email.triage-output.v1";

    public static final String STAGE_TRIAGE = "email-triage";

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
                        List.of(WorkflowStage.agent(EmailTriageAgent.AGENT_ID, 1)),
                        // Una inferencia corta contra un modelo local. Un minuto es holgado; si se
                        // agota, el problema es el modelo o el tamaño del contexto, no el timeout.
                        Duration.ofMinutes(1),
                        "Clasifica un correo: categoría, prioridad, si necesita respuesta,"
                                + " resumen y acción sugerida. Prompt "
                                + EmailTriagePrompts.VERSION));
    }
}
