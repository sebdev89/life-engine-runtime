package io.lifeengine.runtime.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.runtime.observability.RuntimeMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * Regresión de KAN-290: la sonda {@code llmReachable} daba {@code false} contra un proveedor sano.
 *
 * <p>La sonda pegaba a {@code /health}, endpoint propio de vLLM que Ollama —el proveedor real de
 * este runtime— no sirve. El resultado era un siempre-rojo: la sonda no distinguía un proveedor
 * caído de uno vivo, y por lo tanto no era señal de nada.
 *
 * <p>Estos tests fijan las dos direcciones. Una sonda que sólo se prueba en verde puede estar
 * clavada en verde, y una que sólo se prueba en rojo puede estar clavada en rojo; ninguno de los
 * dos casos lo detecta un único test.
 */
class LlmHealthProbeTest {

    private static final String CONFIGURED_MODEL = "qwen3:14b";

    private MockWebServer provider;

    @BeforeEach
    void startProvider() throws IOException {
        provider = new MockWebServer();
        provider.start();
    }

    @AfterEach
    void stopProvider() throws IOException {
        provider.close();
    }

    /**
     * El test que hubiera atrapado KAN-290. El dispatcher imita a Ollama: 404 en {@code /health},
     * 200 en {@code /v1/models}. Con la sonda vieja este caso daba {@code false}.
     */
    @Test
    void probeIsGreenAgainstOllamaWhichDoesNotServeHealth() throws Exception {
        provider.setDispatcher(ollamaLikeDispatcher(CONFIGURED_MODEL));

        StepVerifier.create(client().health()).expectNext(true).verifyComplete();

        RecordedRequest recorded = provider.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("GET");
        assertThat(recorded.getPath())
                .as("la sonda debe usar un endpoint de la API OpenAI-compatible, no /health")
                .isEqualTo("/v1/models");
    }

    @Test
    void probeIsRedWhenProviderIsUpButDoesNotServeTheConfiguredModel() {
        provider.setDispatcher(ollamaLikeDispatcher("gemma3:4b"));

        StepVerifier.create(client().health()).expectNext(false).verifyComplete();
    }

    @Test
    void probeIsRedWhenProviderReturnsServerError() {
        provider.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        StepVerifier.create(client().health()).expectNext(false).verifyComplete();
    }

    @Test
    void probeIsRedWhenProviderHasNoModelsLoaded() {
        provider.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"object\":\"list\",\"data\":[]}"));

        StepVerifier.create(client().health()).expectNext(false).verifyComplete();
    }

    /** Proveedor apagado: el puerto ya no acepta conexiones. Es el rojo que de verdad importa. */
    @Test
    void probeIsRedWhenProviderIsUnreachable() throws IOException {
        OpenAiCompatibleLlmClient client = client();
        provider.close();

        StepVerifier.create(client.health()).expectNext(false).verifyComplete();
    }

    /** Ollama publica {@code nombre:latest}; configurar {@code nombre} apunta al mismo modelo. */
    @Test
    void probeAcceptsTheImplicitLatestTag() {
        provider.setDispatcher(ollamaLikeDispatcher("nomic-embed-text:latest"));

        StepVerifier.create(client("nomic-embed-text").health()).expectNext(true).verifyComplete();
    }

    private static Dispatcher ollamaLikeDispatcher(String servedModel) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/v1/models".equals(request.getPath())) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    "{\"object\":\"list\",\"data\":[{\"id\":\""
                                            + servedModel
                                            + "\",\"object\":\"model\",\"owned_by\":\"library\"}]}");
                }
                // Ollama no sirve /health — devuelve 404, igual que en producción.
                return new MockResponse().setResponseCode(404).setBody("404 page not found");
            }
        };
    }

    private OpenAiCompatibleLlmClient client() {
        return client(CONFIGURED_MODEL);
    }

    private OpenAiCompatibleLlmClient client(String configuredModel) {
        String baseUrl = provider.url("/").toString().replaceAll("/$", "");
        RuntimeLlmProperties props =
                new RuntimeLlmProperties(
                        baseUrl, configuredModel, "test", Duration.ofSeconds(5), 256, 0.0);
        return new OpenAiCompatibleLlmClient(
                WebClient.builder().baseUrl(baseUrl).build(),
                props,
                new RuntimeMetrics(new SimpleMeterRegistry()));
    }
}
