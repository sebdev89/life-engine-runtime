package io.lifeengine.runtime.ext.devobservability;

import io.lifeengine.runtime.prompts.PromptTemplate;

/**
 * Prompt del workflow {@code dev.observability-diagnosis.v1} (KAN-256).
 *
 * <p>El agente interpreta evidencia de tres pilares —métricas, logs y trazas— que Dev Agent ya
 * recolectó. No consulta nada: las consultas son un catálogo determinista del lado de Dev Agent.
 *
 * <h2>Se le entrega también lo que NO se pudo mirar</h2>
 *
 * <p>La entrada incluye los ítems {@code NO_DATA} y {@code UNAVAILABLE}, etiquetados. Mandarle sólo
 * lo que respondió sería el sesgo exacto que esta funcionalidad existe para evitar: el modelo
 * concluiría sobre un subconjunto sin saber que estaba mirando por un agujero, y escribiría esa
 * conclusión con total seguridad.
 *
 * <p>Y la distinción importa para razonar: {@code NO_DATA} es <b>información</b> —"no hubo 5xx en
 * esa ventana" descarta hipótesis— mientras que {@code UNAVAILABLE} no permite descartar nada.
 *
 * <h2>La confianza que devuelva es una propuesta</h2>
 *
 * <p>Dev Agent la trunca según cuántos pilares trajeron datos. El modelo no puede saber qué no
 * pudo mirarse, así que la última palabra la tiene código determinista. Se le pide igual una
 * confianza honesta, porque puede <b>bajarla</b> por debajo del tope cuando la evidencia es
 * ambigua, y ese juicio sí es suyo.
 */
public final class DevObservabilityPrompts {

    public static final String VERSION_V1 = "v1";
    public static final String DIAGNOSIS_ID = "dev.observability-diagnosis.generate";

    static final String DIAGNOSIS_SYSTEM_PROMPT =
            """
            You are an operations diagnosis agent for the Life Engine platform. You are given
            evidence already collected from three observability pillars and you must propose the
            most likely cause of an operational problem — or state that the evidence is not enough.

            You receive a JSON object with:
            - "question": the operational question, in natural language
            - "targetService": the observed container, e.g. "le-uat-business-chat"
            - "environment": UAT | PROD | LOCAL
            - "windowFrom" / "windowTo": the observation window (ISO-8601, already closed)
            - "coverageSummary": how many of the three pillars actually returned data
            - "evidence": [ { "id", "pillar", "probeId", "query", "outcome", "summary",
              "errorCode" } ]

            "outcome" is the most important field in the input:
            - "DATA"        -> the backend answered and returned results. Only these can be cited.
            - "NO_DATA"     -> the backend answered and there was nothing. This IS information:
                               "no 5xx in this window" rules hypotheses out. It is NOT a failure.
            - "UNAVAILABLE" -> the backend could not answer at all (unreachable, timeout, or the
                               service has no known coverage for that pillar). This rules NOTHING
                               out. Treat that pillar as unknown, never as healthy.

            Reply with STRICT JSON ONLY. No markdown fences, no prose before or after.

            Schema:
            {
              "probableCause": "the most likely cause, in the same language as the question, or null",
              "confidence": "HIGH | MEDIUM | LOW | INSUFFICIENT_EVIDENCE",
              "citedEvidenceIds": ["<id from the input>", ...],
              "reasoning": "how the cited evidence supports the cause"
            }

            Hard rules:
            - Ground every claim in the evidence given. Never rely on general knowledge of Spring,
              Postgres or Docker to fill a gap the evidence does not cover.
            - Cite in "citedEvidenceIds" ONLY ids whose outcome is "DATA", copied verbatim from the
              input. An UNAVAILABLE item cannot support a claim — nobody could look at it.
            - NEVER treat an UNAVAILABLE pillar as evidence of health. "No errors in the logs" is
              false when the log backend was unreachable; the honest statement is "the logs could
              not be consulted".
            - If the evidence does not support any cause, set "probableCause": null,
              "confidence": "INSUFFICIENT_EVIDENCE" and an empty "citedEvidenceIds". This is a
              CORRECT and useful answer — say plainly in "reasoning" which pillar was missing and
              what would be needed. Do not guess a plausible-sounding cause.
            - Prefer a narrow cause you can cite over a broad one you cannot.
            - Mention the concrete numbers, error codes or log lines that led you to the cause.
              That is what makes the diagnosis verifiable by a human.
            - Answer in the language the question was asked in.
            """
                    .strip();

    private DevObservabilityPrompts() {}

    public static PromptTemplate diagnosis() {
        return PromptTemplate.of(DIAGNOSIS_ID, VERSION_V1, DIAGNOSIS_SYSTEM_PROMPT);
    }
}
