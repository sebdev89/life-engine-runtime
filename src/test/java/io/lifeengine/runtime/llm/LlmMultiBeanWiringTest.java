package io.lifeengine.runtime.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.runtime.app.RuntimeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that Fase 1 multi-model bean wiring is correct:
 *
 * <ul>
 *   <li>The four role beans ({@code fast}, {@code chat}, {@code smart}, {@code coding}) are
 *       registered and each points to the right model.
 *   <li>Unqualified {@link LlmClient} injection still resolves to the {@code @Primary} default
 *       bean (backward-compat for all agents not yet migrated).
 *   <li>Role beans are distinct instances — no shared state between roles.
 * </ul>
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LlmMultiBeanWiringTest {

    @Autowired
    LlmClient defaultLlmClient;

    @Autowired
    @Qualifier("fastLlmClient")
    LlmClient fastLlmClient;

    @Autowired
    @Qualifier("chatLlmClient")
    LlmClient chatLlmClient;

    @Autowired
    @Qualifier("smartLlmClient")
    LlmClient smartLlmClient;

    @Autowired
    @Qualifier("codingLlmClient")
    LlmClient codingLlmClient;

    @Test
    void defaultClient_resolvesViaAtPrimary_withLegacyModel() {
        assertThat(defaultLlmClient.defaultModel()).isEqualTo("test-model");
    }

    @Test
    void fastLlmClient_pointsToFastModel() {
        assertThat(fastLlmClient.defaultModel()).isEqualTo("test-fast-model");
    }

    @Test
    void chatLlmClient_pointsToChatModel() {
        assertThat(chatLlmClient.defaultModel()).isEqualTo("test-chat-model");
    }

    @Test
    void smartLlmClient_pointsToSmartModel() {
        assertThat(smartLlmClient.defaultModel()).isEqualTo("test-smart-model");
    }

    @Test
    void codingLlmClient_pointsToCodingModel() {
        assertThat(codingLlmClient.defaultModel()).isEqualTo("test-coding-model");
    }

    @Test
    void roleBeans_areDistinctInstances() {
        assertThat(fastLlmClient).isNotSameAs(defaultLlmClient);
        assertThat(chatLlmClient).isNotSameAs(defaultLlmClient);
        assertThat(fastLlmClient).isNotSameAs(chatLlmClient);
        assertThat(smartLlmClient).isNotSameAs(codingLlmClient);
    }

    @Test
    void allBeans_exposeHealthEndpoint() {
        assertThat(defaultLlmClient.chatCompletionsEndpoint()).isNotBlank();
        assertThat(fastLlmClient.chatCompletionsEndpoint()).isNotBlank();
        assertThat(chatLlmClient.chatCompletionsEndpoint()).isNotBlank();
        assertThat(smartLlmClient.chatCompletionsEndpoint()).isNotBlank();
        assertThat(codingLlmClient.chatCompletionsEndpoint()).isNotBlank();
    }

    // --- Fase 2: modelRole() assertions ---

    @Test
    void defaultClient_hasNullModelRole() {
        assertThat(defaultLlmClient.modelRole()).isNull();
    }

    @Test
    void fastLlmClient_hasFastRole() {
        assertThat(fastLlmClient.modelRole()).isEqualTo(LlmModelRole.FAST);
    }

    @Test
    void chatLlmClient_hasChatRole() {
        assertThat(chatLlmClient.modelRole()).isEqualTo(LlmModelRole.CHAT);
    }

    @Test
    void smartLlmClient_hasSmartRole() {
        assertThat(smartLlmClient.modelRole()).isEqualTo(LlmModelRole.SMART);
    }

    @Test
    void codingLlmClient_hasCodingRole() {
        assertThat(codingLlmClient.modelRole()).isEqualTo(LlmModelRole.CODING);
    }
}
