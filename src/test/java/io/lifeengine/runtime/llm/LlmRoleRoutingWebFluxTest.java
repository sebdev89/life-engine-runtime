package io.lifeengine.runtime.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.api.RunResponse;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.domain.RunStatus;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Verifica el ruteo por rol de Multi-Model V2 mirando <b>qué modelo llega al proveedor</b>.
 *
 * <p>Que Spring levante tres beans no prueba nada: el bug que importa es que un agente termine
 * hablando con el modelo equivocado, y eso sólo se ve en el cuerpo de la request HTTP. Por eso acá
 * hay TRES MockWebServer distintos —default, chat y fast—, cada uno con su propio modelo. El
 * destino de cada llamada queda determinado por el puerto que recibió la request, no por
 * introspección de beans.
 *
 * <p>El mock del rol default existe para que una regresión sea ruidosa: si un {@code @Qualifier} se
 * cae, el agente no falla, se va silenciosamente al cliente {@code @Primary}. Afirmar que el
 * default recibió CERO requests es lo que convierte esa caída silenciosa en un test rojo.
 */
@SpringBootTest(
        classes = RuntimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class LlmRoleRoutingWebFluxTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DEFAULT_MODEL = "default-role-model";
    private static final String CHAT_MODEL = "chat-role-model";
    private static final String FAST_MODEL = "fast-role-model";

    private static MockWebServer mockDefault;
    private static MockWebServer mockChat;
    private static MockWebServer mockFast;

    @Autowired private WebTestClient webTestClient;

    @Autowired private LlmClient primaryLlmClient;

    @Autowired
    @Qualifier("chatLlmClient")
    private LlmClient chatLlmClient;

    @Autowired
    @Qualifier("fastLlmClient")
    private LlmClient fastLlmClient;

    @BeforeAll
    static void startMocks() throws IOException {
        mockDefault = new MockWebServer();
        mockDefault.start();
        mockChat = new MockWebServer();
        mockChat.start();
        mockFast = new MockWebServer();
        mockFast.start();
    }

    @AfterAll
    static void stopMocks() throws IOException {
        for (MockWebServer server : List.of(mockDefault, mockChat, mockFast)) {
            if (server != null) {
                server.shutdown();
            }
        }
    }

    @DynamicPropertySource
    static void roleEndpoints(DynamicPropertyRegistry registry) {
        registry.add("runtime.llm.base-url", () -> baseUrl(mockDefault));
        registry.add("runtime.llm.model", () -> DEFAULT_MODEL);
        registry.add("runtime.llm.api-key", () -> "test-key");
        registry.add("runtime.llm.roles.chat.base-url", () -> baseUrl(mockChat));
        registry.add("runtime.llm.roles.chat.model", () -> CHAT_MODEL);
        registry.add("runtime.llm.roles.fast.base-url", () -> baseUrl(mockFast));
        registry.add("runtime.llm.roles.fast.model", () -> FAST_MODEL);
    }

    /**
     * {@code demo.llm.workflow} encadena summarizer → classifier. Los dos piden el rol {@code fast},
     * así que las DOS llamadas tienen que aterrizar en el proveedor fast con el modelo fast.
     */
    @Test
    void summarizerAndClassifier_routeToFastProvider_withFastModel() throws Exception {
        int chatBefore = mockChat.getRequestCount();
        int defaultBefore = mockDefault.getRequestCount();
        enqueue(
                mockFast,
                """
                {"incident":"CPU al 92% en node-3","affectedResource":"node-3","requestedAction":"Revisar autoscaling"}
                """);
        enqueue(mockFast, """
                {"category":"ACTION","reason":"Pide revisar el autoscaling."}
                """);

        UUID runId =
                startRun(
                        """
                        {"workflowId":"demo.llm.workflow","input":"[INCIDENT] CPU 92% en node-3. [ACTION REQUIRED] Revisar autoscaling."}
                        """);
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        Assertions.assertThat(modelsReceivedBy(mockFast, 2))
                .as("summarizer y classifier salen por el proveedor del rol fast")
                .containsExactly(FAST_MODEL, FAST_MODEL);

        Assertions.assertThat(mockChat.getRequestCount() - chatBefore)
                .as("ningún agente fast debe caer en el proveedor chat")
                .isZero();
        Assertions.assertThat(mockDefault.getRequestCount() - defaultBefore)
                .as("un @Qualifier caído se iría en silencio al cliente @Primary")
                .isZero();
    }

    /**
     * {@code business-chat.reply.v1} encadena business-context → business-reply. Los dos piden el
     * rol {@code chat}: es la respuesta que termina leyendo una persona.
     */
    @Test
    void businessContextAndReply_routeToChatProvider_withChatModel() throws Exception {
        int fastBefore = mockFast.getRequestCount();
        int defaultBefore = mockDefault.getRequestCount();
        enqueue(
                mockChat,
                """
                {"intent":"pricing","confidence":"HIGH","handoffRequired":false,"leadCaptured":false,"contextNotes":"Consulta de precios."}
                """);
        enqueue(
                mockChat,
                """
                {"response":"El combo sale $12000.","intent":"pricing","confidence":"HIGH","handoffRequired":false,"leadCaptured":false,"channel":"WEB_CHAT"}
                """);

        UUID runId =
                startRun(
                        """
                        {
                          "workflowId":"business-chat.reply.v1",
                          "input":"{\\"channel\\":\\"WEB_CHAT\\",\\"botId\\":\\"barberia-demo\\",\\"conversationId\\":\\"role-routing-conv\\",\\"customer\\":{\\"name\\":\\"Cliente Demo\\",\\"externalId\\":\\"web-demo-1\\"},\\"message\\":\\"Cuanto sale corte y barba?\\"}",
                          "correlationId":"role-routing-chat"
                        }
                        """);
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        Assertions.assertThat(modelsReceivedBy(mockChat, 2))
                .as("business-context y business-reply salen por el proveedor del rol chat")
                .containsExactly(CHAT_MODEL, CHAT_MODEL);

        Assertions.assertThat(mockFast.getRequestCount() - fastBefore)
                .as("ningún agente chat debe caer en el proveedor fast")
                .isZero();
        Assertions.assertThat(mockDefault.getRequestCount() - defaultBefore)
                .as("un @Qualifier caído se iría en silencio al cliente @Primary")
                .isZero();
    }

    /**
     * Los tres clientes son instancias distintas, apuntadas a proveedores distintos, y el inyectado
     * sin qualifier es el {@code @Primary} — o sea, el default, no un rol.
     */
    @Test
    void threeDistinctClients_andUnqualifiedInjectionResolvesToDefault() {
        Assertions.assertThat(primaryLlmClient.defaultModel()).isEqualTo(DEFAULT_MODEL);
        Assertions.assertThat(chatLlmClient.defaultModel()).isEqualTo(CHAT_MODEL);
        Assertions.assertThat(fastLlmClient.defaultModel()).isEqualTo(FAST_MODEL);

        Assertions.assertThat(primaryLlmClient.chatCompletionsEndpoint())
                .startsWith(baseUrl(mockDefault));
        Assertions.assertThat(chatLlmClient.chatCompletionsEndpoint()).startsWith(baseUrl(mockChat));
        Assertions.assertThat(fastLlmClient.chatCompletionsEndpoint()).startsWith(baseUrl(mockFast));

        Assertions.assertThat(primaryLlmClient).isNotSameAs(chatLlmClient).isNotSameAs(fastLlmClient);
        Assertions.assertThat(chatLlmClient).isNotSameAs(fastLlmClient);
    }

    /** Los roles no declaran retry ni response-format, así que los heredan del default. */
    @Test
    void rolesInheritRetryAndResponseFormatFromDefaults() throws Exception {
        Assertions.assertThat(chatLlmClient.retryConfig()).isEqualTo(primaryLlmClient.retryConfig());
        Assertions.assertThat(fastLlmClient.retryConfig()).isEqualTo(primaryLlmClient.retryConfig());

        enqueue(mockFast, """
                {"incident":"x","affectedResource":"y","requestedAction":"z"}
                """);
        enqueue(mockFast, """
                {"category":"INFO","reason":"n/a"}
                """);
        UUID runId =
                startRun(
                        """
                        {"workflowId":"demo.llm.workflow","input":"herencia de response-format"}
                        """);
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        // Se drenan las DOS requests: dejar una sin consumir contamina el orden de la cola grabada
        // para cualquier test que corra después en este mismo contexto.
        JsonNode first = JSON.readTree(takeRequest(mockFast).getBody().readUtf8());
        JsonNode second = JSON.readTree(takeRequest(mockFast).getBody().readUtf8());

        for (JsonNode body : List.of(first, second)) {
            Assertions.assertThat(body.path("response_format").path("type").asText())
                    .as("el rol hereda response-format del default (json_object)")
                    .isEqualTo("json_object");
            Assertions.assertThat(body.path("model").asText()).isEqualTo(FAST_MODEL);
        }
    }

    // ------------------------------------------------------------------ helpers ----

    private static String baseUrl(MockWebServer server) {
        return server.url("/").toString().replaceAll("/$", "");
    }

    /** Modelos que efectivamente viajaron en el cuerpo de las {@code count} requests del mock. */
    private static List<String> modelsReceivedBy(MockWebServer server, int count) throws Exception {
        List<String> models = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RecordedRequest request = takeRequest(server);
            Assertions.assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
            models.add(JSON.readTree(request.getBody().readUtf8()).path("model").asText());
        }
        return models;
    }

    private static RecordedRequest takeRequest(MockWebServer server) throws InterruptedException {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        Assertions.assertThat(request).as("el proveedor esperaba una request que nunca llegó").isNotNull();
        return request;
    }

    private static void enqueue(MockWebServer server, String content) {
        try {
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    """
                                    {
                                      "choices": [{"message": {"content": %s}}],
                                      "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                                    }
                                    """
                                            .formatted(JSON.writeValueAsString(content.trim()))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID startRun(String json) {
        return webTestClient
                .post()
                .uri("/api/runtime/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(RunResponse.class)
                .returnResult()
                .getResponseBody()
                .runId();
    }

    private void awaitTerminal(UUID runId, RunStatus expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        RunStatus last = null;
        while (System.nanoTime() < deadline) {
            last =
                    webTestClient
                            .get()
                            .uri("/api/runtime/runs/{runId}", runId)
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(RunResponse.class)
                            .returnResult()
                            .getResponseBody()
                            .status();
            if (last == expected) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        Assertions.fail("Run no llegó a " + expected + " (quedó en " + last + ")");
    }
}
