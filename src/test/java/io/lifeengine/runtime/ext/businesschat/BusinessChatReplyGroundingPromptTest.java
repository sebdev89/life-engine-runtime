package io.lifeengine.runtime.ext.businesschat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.prompts.PromptTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * KAN-293 — política de grounding del reply agent.
 *
 * <p>El bot de un estudio jurídico recuperaba los fragmentos correctos y después no los usaba:
 * contestaba "no se especifica el plazo" con el plazo textual entre las fuentes, y en un caso de
 * accidente laboral omitió la denuncia ante la ART —que dispara todos los plazos— mientras inventaba
 * un "informe de la empresa" inexistente en el corpus.
 *
 * <p>La causa era el texto del prompt, no el modelo ni la recuperación. Toda regla anti-invención
 * estaba acotada a vocabulario de e-commerce ("prices, policies, or hours"), de modo que inventar un
 * requisito documental no violaba ninguna regla vigente; y el grounding era advisory ("prioritize")
 * en lugar de vinculante.
 *
 * <p>Estos tests son un cinturón de seguridad sobre el TEXTO del prompt, no sobre la calidad de las
 * respuestas. Que una regla esté presente no prueba que el modelo la obedezca: el juez real es el
 * conjunto dorado. Lo que sí evitan es que la regla desaparezca en una edición futura sin que nadie
 * se entere, que es exactamente como nació este defecto.
 */
class BusinessChatReplyGroundingPromptTest {

    private static final String REPLY_PROMPT = BusinessChatReplyPrompts.REPLY_SYSTEM_PROMPT;

    /**
     * El prompt es un text block: las reglas largas vienen cortadas por saltos de línea e
     * indentación, y un assert de substring sobre el crudo fallaría por el wrapping y no por el
     * contenido. Los asserts sobre REGLAS usan esta vista con el whitespace colapsado; los asserts
     * sobre el ESQUEMA usan el crudo, donde el formato sí importa.
     */
    private static final String FLAT = REPLY_PROMPT.replaceAll("\\s+", " ");

    @Nested
    @DisplayName("la política de grounding es vinculante y no sólo comercial")
    class GroundingPolicy {

        @Test
        void groundingIsDeclaredBindingAndOverriding() {
            assertThat(FLAT).contains("GROUNDING POLICY — binding");
            assertThat(FLAT)
                    .as("sin precedencia explícita, tono y brevedad compiten con el grounding")
                    .contains("It overrides every other instruction below");
        }

        @Test
        void groundingIsNotAdvisory() {
            assertThat(FLAT)
                    .as("'prioritize' dejaba el grounding como sugerencia — ver KAN-293")
                    .doesNotContain("prioritize those fragments");
            assertThat(FLAT).contains("MUST be supported by");
        }

        @Test
        void antiInventionRulesAreNotLimitedToCommerceVocabulary() {
            assertThat(FLAT)
                    .as("la regla vieja sólo cubría vocabulario de e-commerce")
                    .doesNotContain("Do not invent prices, policies, or hours")
                    .doesNotContain("Never invent services, prices, or hours");
        }

        /**
         * El corazón de KAN-293: las categorías de hecho que el bot inventaba u omitía tienen que
         * estar nombradas, porque el modelo aplicó la regla vieja al pie de la letra.
         */
        @Test
        void factCategoriesBeyondCommerceAreNamedExplicitly() {
            assertThat(FLAT)
                    .contains("required documents")
                    .contains("deadlines and time limits")
                    .contains("procedural steps")
                    .contains("obligations")
                    .contains("entitlements");
        }

        @Test
        void modelGeneralKnowledgeIsExcludedAsASource() {
            assertThat(FLAT).contains("Your own general knowledge is NOT a source");
        }

        /** "informe de la empresa" era una completación plausible de una lista parcial. */
        @Test
        void plausibleCompletionOfAPartialListIsForbidden() {
            assertThat(FLAT).contains("not as a plausible completion of a partial list");
        }

        /** Omitir la denuncia ante la ART fue más grave que inventar. La regla debe decirlo. */
        @Test
        void omissionIsDeclaredAsHarmfulAsInvention() {
            assertThat(FLAT)
                    .contains("Omitting an item the sources DO contain")
                    .contains("as harmful as inventing one");
        }
    }

    @Nested
    @DisplayName("extracción fiel por sobre redacción libre")
    class FaithfulExtraction {

        @Test
        void listAnswersMustBeCopiedCompletely() {
            assertThat(FLAT)
                    .contains("copy")
                    .contains("EVERY item the sources give")
                    .contains("do not drop items you judge secondary");
        }

        @Test
        void sourceWordingIsPreservedForNamedEntities() {
            assertThat(FLAT)
                    .contains("Keep the source's own wording")
                    .contains("Faithful extraction outranks fluent rewriting");
        }

        /** "no se especifica el tiempo exacto" con "2 años" textual en las fuentes. */
        @Test
        void concreteValuesPresentInSourcesMustBeAnswered() {
            assertThat(FLAT)
                    .contains("Never answer that the value is unspecified, unknown, or unavailable")
                    .contains("while it is present in the sources");
        }
    }

    @Nested
    @DisplayName("abstención y derivación cuando el corpus no alcanza")
    class AbstentionAndHandoff {

        @Test
        void partialAnswersMustSayWhatCannotBeConfirmed() {
            assertThat(FLAT)
                    .contains("say explicitly which part you cannot confirm")
                    .contains("Never close the gap with an invented answer");
        }

        @Test
        void handoffIsNotAnEscapeFromAnAnswerableQuestion() {
            assertThat(FLAT)
                    .contains("never as a way to avoid answering something the sources already cover");
        }
    }

    @Nested
    @DisplayName("location/schedule ya no se restringe a las FAQs")
    class LocationIntent {

        /**
         * "¿Dónde queda el estudio?" derivaba a WhatsApp con 4 fuentes recuperadas. La regla vieja
         * mandaba responder desde las FAQs ÚNICAMENTE, y la dirección vive en el documento
         * institucional, que viaja como chunk y no como FAQ — son secciones distintas del knowledge
         * base que arma BusinessKnowledgeService.
         */
        @Test
        void locationAnswersMayUseRetrievedChunks() {
            assertThat(FLAT)
                    .as("la restricción a FAQs excluía el chunk que sí tenía el dato")
                    .doesNotContain("answer from the knowledge base FAQs only");
            assertThat(FLAT)
                    .contains("answer from whichever source actually holds the data")
                    .contains("do not defer to a human for a datum you were given");
        }
    }

    @Nested
    @DisplayName("las fuentes se conservan y se muestran")
    class Sources {

        @Test
        void everyReliedUponChunkMustBeListed() {
            assertThat(FLAT)
                    .contains("Include sources whenever retrievedChunks supported the answer")
                    .contains("list every chunk you actually relied on");
        }

        @Test
        void sourcesMustReferenceRealChunks() {
            assertThat(FLAT)
                    .contains("each source must reference a chunk from")
                    .contains("businessContext.retrievedChunks");
        }
    }

    /**
     * Contratos congelados. El arreglo de KAN-293 es texto de reglas y nada más; si esta clase se
     * pone roja, el cambio dejó de ser un cambio de prompt y necesita ADR.
     */
    @Nested
    @DisplayName("contratos congelados intactos")
    class FrozenContracts {

        @Test
        void workflowIdIsUnchanged() {
            assertThat(BusinessChatReplyModule.WORKFLOW_ID).isEqualTo("business-chat.reply.v1");
        }

        @Test
        void promptTemplateIdsAndVersionAreUnchanged() {
            assertThat(BusinessChatReplyPrompts.REPLY_ID).isEqualTo("business-chat.reply.generate");
            assertThat(BusinessChatReplyPrompts.CONTEXT_ID).isEqualTo("business-chat.reply.context");
            assertThat(BusinessChatReplyPrompts.LEAD_CAPTURE_ID)
                    .isEqualTo("business-chat.reply.lead-capture");
            assertThat(BusinessChatReplyPrompts.VERSION_V1).isEqualTo("v1");

            PromptTemplate reply = BusinessChatReplyPrompts.reply();
            assertThat(reply.id()).isEqualTo(BusinessChatReplyPrompts.REPLY_ID);
            assertThat(reply.version()).isEqualTo(BusinessChatReplyPrompts.VERSION_V1);
        }

        /** El esquema que el prompt le pide al modelo es la forma de la salida. No se toca. */
        @Test
        void outputSchemaKeysAreUnchanged() {
            assertThat(REPLY_PROMPT)
                    .contains("\"response\"")
                    .contains("\"intent\"")
                    .contains("\"confidence\"")
                    .contains("\"handoffRequired\"")
                    .contains("\"leadCaptured\"")
                    .contains("\"channel\"")
                    .contains("\"sources\"")
                    .contains("\"chunkId\"");
        }

        @Test
        void strictJsonOnlyContractIsUnchanged() {
            assertThat(REPLY_PROMPT)
                    .contains("Reply with STRICT JSON ONLY")
                    .contains("No markdown fences");
        }

        /**
         * El prompt declara un esquema JSON dentro de un bloque de texto. Si ese bloque dejara de
         * ser JSON parseable, el modelo recibiría una especificación ambigua y ningún test de
         * substring lo notaría.
         */
        @Test
        void declaredSchemaBlockIsStillValidJson() throws Exception {
            String schema = extractFirstJsonObject(REPLY_PROMPT);
            assertThat(new ObjectMapper().readTree(schema).fieldNames()).hasNext();
        }
    }

    /** Todas las plantillas siguen siendo construibles — un prompt en blanco explota en runtime. */
    @Test
    void allTemplatesRemainConstructible() {
        assertThat(BusinessChatReplyPrompts.reply().systemMessage()).isNotBlank();
        assertThat(BusinessChatReplyPrompts.context().systemMessage()).isNotBlank();
        assertThat(BusinessChatReplyPrompts.leadCapture().systemMessage()).isNotBlank();
    }

    private static String extractFirstJsonObject(String text) {
        int start = text.indexOf("{\n");
        assertThat(start).as("el prompt debe declarar un bloque de esquema").isGreaterThan(-1);
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("bloque de esquema sin cerrar en el prompt");
    }
}
