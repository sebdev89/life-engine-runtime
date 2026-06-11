package io.lifeengine.runtime.ext.businesschat.api;

import io.lifeengine.runtime.app.RuntimeApplication;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class BusinessChatBotsControllerTest {

    @Autowired private WebTestClient webTestClient;

    @Test
    void getBot_returnsReadOnlyProfile() {
        BusinessBotProfileView profile =
                webTestClient
                        .get()
                        .uri("/api/runtime/business-chat/bots/barberia-demo")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(BusinessBotProfileView.class)
                        .returnResult()
                        .getResponseBody();

        Assertions.assertThat(profile).isNotNull();
        Assertions.assertThat(profile.botId()).isEqualTo("barberia-demo");
        Assertions.assertThat(profile.businessName()).isEqualTo("Barbería Demo");
        Assertions.assertThat(profile.tone()).contains("WhatsApp");
        Assertions.assertThat(profile.rules()).isNotEmpty();
        Assertions.assertThat(profile.faqs()).isNotEmpty();
        Assertions.assertThat(profile.faqs().getFirst().question()).contains("corte");
        Assertions.assertThat(profile.suggestedPrompts()).hasSize(4);
        Assertions.assertThat(profile.suggestedPrompts().getFirst().label()).isEqualTo("Precios");
        Assertions.assertThat(profile.suggestedPrompts().get(3).label()).isEqualTo("Reservar turno");
    }

    @Test
    void getBot_unknownBotId_returns404() {
        webTestClient
                .get()
                .uri("/api/runtime/business-chat/bots/unknown-bot")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("not_found");
    }
}
