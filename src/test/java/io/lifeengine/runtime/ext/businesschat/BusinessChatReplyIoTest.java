package io.lifeengine.runtime.ext.businesschat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class BusinessChatReplyIoTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void readInput_parsesConversationHistoryAndBotProfile() throws Exception {
        BusinessChatReplyIo.Input input =
                BusinessChatReplyIo.readInput(
                        JSON,
                        """
                        {
                          "channel": "WEB_CHAT",
                          "botId": "consultorio-demo",
                          "conversationId": "conv-42",
                          "customer": { "name": "María", "externalId": "web-1" },
                          "message": "¿Cuánto cuesta una consulta inicial?",
                          "conversationHistory": [
                            {
                              "customerMessage": "Hola",
                              "botResponse": "Hola, ¿en qué te puedo ayudar?"
                            }
                          ],
                          "botProfile": {
                            "tone": "profesional y cercano",
                            "personality": "abogado laboralista",
                            "greetingStyle": "breve"
                          }
                        }
                        """);

        Assertions.assertThat(input.conversationHistory()).hasSize(1);
        Assertions.assertThat(input.conversationHistory().get(0).customerMessage()).isEqualTo("Hola");
        Assertions.assertThat(input.conversationHistory().get(0).botResponse())
                .isEqualTo("Hola, ¿en qué te puedo ayudar?");
        Assertions.assertThat(input.botProfile().tone()).isEqualTo("profesional y cercano");
        Assertions.assertThat(input.botProfile().personality()).isEqualTo("abogado laboralista");
        Assertions.assertThat(input.botProfile().greetingStyle()).isEqualTo("breve");
    }

    @Test
    void readInput_worksWithoutOptionalFields() throws Exception {
        BusinessChatReplyIo.Input input =
                BusinessChatReplyIo.readInput(
                        JSON,
                        """
                        {
                          "channel": "WHATSAPP",
                          "botId": "barberia-demo",
                          "conversationId": "conv-1",
                          "customer": { "name": "Cliente", "externalId": "wa-1" },
                          "message": "Hola"
                        }
                        """);

        Assertions.assertThat(input.conversationHistory()).isNull();
        Assertions.assertThat(input.botProfile()).isNull();
        Assertions.assertThat(input.businessContext()).isNull();
    }

    @Test
    void botProfileForLlm_omitsNullFields() {
        Map<String, Object> profile =
                BusinessChatReplyIo.botProfileForLlm(
                        new BusinessChatReplyIo.BotProfile(
                                null, "formal", "experto en inmuebles", null, List.of("No inventar precios")));

        Assertions.assertThat(profile)
                .containsEntry("tone", "formal")
                .containsEntry("personality", "experto en inmuebles")
                .containsEntry("rules", List.of("No inventar precios"))
                .doesNotContainKey("businessName")
                .doesNotContainKey("greetingStyle");
    }
}
