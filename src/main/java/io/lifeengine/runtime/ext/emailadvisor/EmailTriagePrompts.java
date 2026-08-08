package io.lifeengine.runtime.ext.emailadvisor;

/**
 * Prompt de clasificación de correo, versionado (KAN-234).
 *
 * <p>La versión viaja con cada clasificación persistida. No es ceremonia: cuando dentro de dos meses
 * una categoría empiece a salir mal, la única forma de saber si el prompt cambió entre medio es
 * haberlo registrado. Sin esto, "el clasificador anda peor" es una afirmación que no se puede
 * investigar.
 *
 * <p><b>Cambiar el texto obliga a subir {@link #VERSION}.</b> Hay un test que compara el hash del
 * prompt contra la versión declarada justamente para que no se pueda olvidar.
 *
 * <p>El vocabulario de categorías y prioridades no se inventa acá: es el que está congelado en el
 * CHECK de {@code email_classifications} (migración V27). Business Chat es dueño de la semántica;
 * Runtime sólo ejecuta la inferencia.
 */
public final class EmailTriagePrompts {

    /** Subir ante cualquier cambio del texto del prompt. */
    public static final String VERSION = "email-triage-v1";

    private EmailTriagePrompts() {}

    public static String system() {
        return """
            Sos un asistente que clasifica correo personal. Respondés SOLO con JSON: sin markdown,
            sin cercos de código, sin texto adicional.

            Esquema exacto:
            {
              "category": "<una de las categorías>",
              "priority": "LOW|NORMAL|HIGH|URGENT",
              "needsReply": true|false,
              "informationOnly": true|false,
              "canBeDrafted": true|false,
              "summary": "<de qué se trata el correo, una oración>",
              "suggestedAction": "<qué debería hacer la persona, una oración; vacío si no hay nada que hacer>",
              "confidence": <número entre 0 y 1>,
              "reason": "<por qué elegiste esa categoría, una oración corta>"
            }

            category debe ser exactamente una de:
            JOB_OPPORTUNITY, RECRUITER, INTERVIEW, COMPANY, HEALTH_INSURANCE, BANK_OR_FINANCE,
            PROVIDER, ADMINISTRATIVE, NEWSLETTER, PERSONAL, SPAM_OR_LOW_PRIORITY, UNCLASSIFIED

            Reglas:
            - Usá UNCLASSIFIED sólo si de verdad no podés decidir, y en ese caso poné confidence baja.
            - needsReply es true sólo si alguien espera una respuesta escrita de esta persona.
            - informationOnly es true sólo si el correo no pide ninguna acción: es puro aviso.
              Si informationOnly es true, needsReply tiene que ser false.
              Lo inverso NO vale: un correo puede no necesitar respuesta y aun así pedir una acción
              (por ejemplo "verificá tu cuenta antes del viernes"), y ahí informationOnly es false y
              suggestedAction describe la acción.
            - canBeDrafted es true si se puede escribir un borrador de respuesta útil sin datos que
              no estén en el correo.
            - summary describe el correo. reason explica tu decisión. No son lo mismo.
            - No inventes datos que no estén en el correo.
            """
                .strip();
    }
}
