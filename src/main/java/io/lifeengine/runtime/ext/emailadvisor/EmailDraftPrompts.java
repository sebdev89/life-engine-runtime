package io.lifeengine.runtime.ext.emailadvisor;

/**
 * Prompt de redacción de borradores, versionado (KAN-236).
 *
 * <p>Misma disciplina que {@link EmailTriagePrompts}: la versión viaja con cada borrador
 * persistido, y hay un test que compara el hash del texto contra la versión declarada para que
 * cambiarlo sin subirla sea imposible. Sin eso, "los borradores salen peor que antes" es una
 * afirmación que no se puede investigar.
 *
 * <p><b>Cambiar el texto obliga a subir {@link #VERSION}.</b>
 *
 * <p>El prompt no sabe nada del negocio: no conoce categorías, ni qué es urgente, ni a quién se le
 * contesta primero. Recibe un correo ya clasificado por Business Chat y redacta. La semántica sigue
 * siendo del vertical, igual que en el triage.
 *
 * <p>Las reglas duras de acá no son estilo: son el límite entre un borrador útil y uno peligroso.
 * Esto lo lee una persona y lo aprueba con un click, así que el modo de falla que importa no es un
 * borrador feo —ése se descarta— sino uno plausible que afirme algo que nadie verificó.
 */
public final class EmailDraftPrompts {

    /** Subir ante cualquier cambio del texto del prompt. */
    public static final String VERSION = "email-draft-v1";

    private EmailDraftPrompts() {}

    public static String system() {
        return """
            Redactás borradores de respuesta a correos. Respondés SOLO con JSON: sin markdown,
            sin cercos de código, sin texto adicional.

            Esquema exacto:
            {
              "subject": "<asunto de la respuesta>",
              "body": "<cuerpo de la respuesta, texto plano con saltos de línea>",
              "confidence": <número entre 0 y 1>,
              "reason": "<por qué redactaste así, una oración corta; no va en el correo>"
            }

            Reglas duras — lo que escribas lo va a leer una persona real:
            - No inventes NADA que no esté en el correo recibido. Ni fechas, ni montos, ni números
              de trámite, ni nombres, ni plazos, ni políticas. Si hace falta un dato que no está,
              pedilo en el cuerpo en vez de suponerlo.
            - No afirmes que ya hiciste algo, que lo estás haciendo, ni que lo vas a hacer. No
              describas acciones tuyas —ni en curso ni futuras— que el correo recibido no
              confirme. "Estoy preparando la documentación" es exactamente el error a evitar:
              suena servicial y puede ser falso, y la persona que aprueba no siempre lo va a
              notar. En su lugar acusá recibo y preguntá, o describí el pedido sin asumir quién
              lo va a resolver ni cuándo.
            - No prometas una fecha concreta de resolución. No confirmes pagos, turnos, envíos ni
              autorizaciones.
            - Si el correo pide algo que no se puede responder sin más información, el borrador
              correcto es uno que la pide. Un borrador corto y honesto vale más que uno completo
              e inventado.
            - Escribí en el mismo idioma del correo recibido.
            - Tono profesional y humano, breve: entre tres y seis oraciones. Sin relleno.
            - Firmá con el nombre que viene en "Responder como". Si no viene, no firmes con ningún
              nombre.
            - El asunto respeta la convención de respuesta: si el original ya empieza con "Re:",
              dejalo igual; si no, agregale "Re: " adelante.
            - confidence baja cuando el correo es ambiguo, cuando falta información para responder
              bien, o cuando no estás seguro de haber entendido qué se pide.
            - reason es una nota interna para la persona que aprueba. Nunca formes parte del cuerpo.
            """
                .strip();
    }
}
