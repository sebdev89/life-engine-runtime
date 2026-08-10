package io.lifeengine.runtime.ext.devobservability;

import io.lifeengine.runtime.ext.devobservability.stages.DevObservabilityDiagnosisAgent;
import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registra {@code dev.observability-diagnosis.v1} — el workflow que interpreta la evidencia que Dev
 * Agent recolecta de Prometheus, Loki y Jaeger (KAN-256, SPEC-008 §5.4).
 *
 * <h2>Qué hace y qué no</h2>
 *
 * <p>Recibe evidencia <b>ya recolectada</b> y propone una causa. No consulta ningún backend: las
 * consultas son un catálogo determinista del lado de Dev Agent, porque una consulta generada por un
 * modelo no es reproducible, no se puede testear, y abre una superficie de inyección PromQL/LogQL.
 *
 * <p>La confianza que devuelve es una <b>propuesta</b>: Dev Agent la trunca según cuántos pilares
 * trajeron datos realmente. El modelo no tiene forma de saber qué no pudo mirarse.
 *
 * <p>Runtime sigue sin lógica de negocio: acá vive el prompt y la forma de la salida, no la política
 * de confianza ni el catálogo de consultas.
 *
 * <h2>El identificador de la etapa es un contrato</h2>
 *
 * <p>{@code STAGE_DIAGNOSIS} tiene que ser exactamente {@code observability-diagnosis}: es lo que
 * {@code DiagnosisClient} busca del lado de Dev Agent. Renombrarlo acá deja la investigación sin
 * diagnóstico, y el síntoma —una investigación que siempre cierra con
 * {@code INSUFFICIENT_EVIDENCE}— no apunta a este archivo. Hay un test que lo fija.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.dev-observability.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DevObservabilityDiagnosisModule implements RuntimeModule {

    public static final String MODULE_ID = "dev-observability";
    public static final String WORKFLOW_ID = "dev.observability-diagnosis.v1";
    public static final String INPUT_CONTRACT = "dev.observability-diagnosis-input.v1";
    public static final String OUTPUT_CONTRACT = "dev.observability-diagnosis-output.v1";

    /** Lo consume {@code DiagnosisClient} en Dev Agent. No renombrar. */
    public static final String STAGE_DIAGNOSIS = "observability-diagnosis";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(RuntimeRegistry registry) {
        registry.registerPromptTemplate(DevObservabilityPrompts.diagnosis());
        registry.registerWorkflow(new WorkflowDefinition(
                WORKFLOW_ID,
                INPUT_CONTRACT,
                OUTPUT_CONTRACT,
                List.of(new WorkflowStage(
                        STAGE_DIAGNOSIS, 1, WorkflowStage.StageKind.AGENT, DevObservabilityDiagnosisAgent.AGENT_ID)),
                // Una sola llamada, pero sobre hasta ocho bloques de evidencia y contra un modelo
                // local. El timeout de etapa es lo único que evita que un run quede RUNNING para
                // siempre si el proveedor no responde.
                Duration.ofMinutes(3),
                "Diagnostica un problema operacional a partir de evidencia de métricas, logs y trazas"));
    }
}
