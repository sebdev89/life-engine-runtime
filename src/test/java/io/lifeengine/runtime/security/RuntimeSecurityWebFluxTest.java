package io.lifeengine.runtime.security;

import io.lifeengine.runtime.api.RunResponse;
import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.app.RuntimeApplication;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "lifeengine.runtime.security.enabled=true",
            "lifeengine.runtime.security.derive-runtime-authorities-from-role=true",
            "lifeengine.security.jwt.secret=" + RuntimeTestJwt.TEST_SECRET,
            "management.endpoints.web.exposure.include=health,info,metrics"
        })
class RuntimeSecurityWebFluxTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void runtimeHealth_isPublic() {
        webTestClient.get().uri("/api/runtime/health").exchange().expectStatus().isOk();
    }

    @Test
    void listWorkflows_withoutToken_returns401() {
        webTestClient
                .get()
                .uri("/api/runtime/workflows")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void listWorkflows_withViewer_returns200() {
        webTestClient
                .get()
                .uri("/api/runtime/workflows")
                .header("Authorization", RuntimeTestJwt.bearer(List.of(RuntimeAuthorities.VIEWER)))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void startRun_withViewerOnly_returns403() {
        webTestClient
                .post()
                .uri("/api/runtime/runs")
                .header("Authorization", RuntimeTestJwt.bearer(List.of(RuntimeAuthorities.VIEWER)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"workflowId\":\"demo.no-llm.workflow\",\"input\":\"hello\",\"correlationId\":\"sec-test\"}")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void startRun_withOperator_returns201() {
        webTestClient
                .post()
                .uri("/api/runtime/runs")
                .header("Authorization", RuntimeTestJwt.bearer(List.of(RuntimeAuthorities.OPERATOR)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"workflowId\":\"demo.no-llm.workflow\",\"input\":\"hello\",\"correlationId\":\"sec-test\"}")
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void listAgents_withViewer_returns200() {
        webTestClient
                .get()
                .uri("/api/runtime/agents")
                .header("Authorization", RuntimeTestJwt.bearer(List.of(RuntimeAuthorities.VIEWER)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].agentId")
                .exists();
    }

    @Test
    void actuatorInfo_withoutAdmin_returns403() {
        webTestClient
                .get()
                .uri("/actuator/info")
                .header("Authorization", RuntimeTestJwt.bearer(List.of(RuntimeAuthorities.VIEWER)))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void actuatorInfo_withAdmin_returns200() {
        webTestClient
                .get()
                .uri("/actuator/info")
                .header("Authorization", RuntimeTestJwt.bearer(List.of(RuntimeAuthorities.ADMIN)))
                .exchange()
                .expectStatus()
                .isOk();
    }

    // --- Phase-1 bridge: role-derived RUNTIME_* authorities ---------------------------------
    //
    // life-engine-auth currently issues JWTs whose `authorities` claim only carries platform
    // permission codes (ROLE_ADMIN / ROLE_USER / AUTH:RBAC:MANAGE / ROLE_GUEST). These tests
    // ensure RuntimeJwtService mints RUNTIME_VIEWER/OPERATOR/ADMIN from the JWT `role` claim
    // and ROLE_ADMIN so the runtime is reachable from auth-ui without a second auth system.
    //
    // When life-engine-auth ships a Flyway migration that seeds RUNTIME_* permissions and
    // assigns them via auth_role_permission, set
    // `lifeengine.runtime.security.derive-runtime-authorities-from-role=false` and update these
    // tests accordingly (or move them to a dedicated profile).

    @Test
    void listWorkflows_withRoleUserOnly_derivesViewerAuthority() {
        webTestClient
                .get()
                .uri("/api/runtime/workflows")
                .header(
                        "Authorization",
                        RuntimeTestJwt.bearerForPlatformRole("USER", List.of("ROLE_USER")))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void startRun_withPlatformRoleAdmin_derivesOperatorAuthority() {
        webTestClient
                .post()
                .uri("/api/runtime/runs")
                .header(
                        "Authorization",
                        RuntimeTestJwt.bearerForPlatformRole(
                                "ADMIN", List.of("ROLE_ADMIN", "ROLE_USER", "AUTH:RBAC:MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"workflowId\":\"demo.no-llm.workflow\",\"input\":\"hello\",\"correlationId\":\"sec-role-admin\"}")
                .exchange()
                .expectStatus()
                .isCreated();
    }

    @Test
    void startRun_withPlatformRoleViewerOnly_returns403() {
        webTestClient
                .post()
                .uri("/api/runtime/runs")
                .header(
                        "Authorization",
                        RuntimeTestJwt.bearerForPlatformRole("VIEWER", List.of("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"workflowId\":\"demo.no-llm.workflow\",\"input\":\"hello\"}")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void actuatorInfo_withPlatformRoleAdmin_derivesAdminAuthority() {
        webTestClient
                .get()
                .uri("/actuator/info")
                .header(
                        "Authorization",
                        RuntimeTestJwt.bearerForPlatformRole("ADMIN", List.of("ROLE_ADMIN")))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void startRun_cryptoMarketReview_withOperator_returns201() {
        webTestClient
                .post()
                .uri("/api/runtime/runs")
                .header("Authorization", RuntimeTestJwt.bearer(List.of(RuntimeAuthorities.OPERATOR)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"workflowId\":\"crypto.market-review.v1\",\"input\":\"{\\\"symbol\\\":\\\"BTCUSDT\\\"}\",\"correlationId\":\"sec-crypto\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.workflowId")
                .isEqualTo("crypto.market-review.v1");
    }

    // --- SSE: `Authorization` header AND `?access_token=` fallback -------------------------

    @Test
    void streamRun_withoutToken_returns401() {
        UUID runId = startNoLlmRun(RuntimeAuthorities.OPERATOR);
        webTestClient
                .get()
                .uri("/api/runtime/runs/{runId}/stream", runId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void streamRun_withAccessTokenQueryParam_returns200() {
        String operatorBearer = RuntimeTestJwt.bearer(List.of(RuntimeAuthorities.OPERATOR));
        UUID runId =
                webTestClient
                        .post()
                        .uri("/api/runtime/runs")
                        .header("Authorization", operatorBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(
                                "{\"workflowId\":\"demo.no-llm.workflow\",\"input\":\"hello\",\"correlationId\":\"sec-sse\"}")
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(RunResponse.class)
                        .returnResult()
                        .getResponseBody()
                        .runId();

        String rawToken = operatorBearer.substring("Bearer ".length());
        List<RuntimeEventResponse> events =
                webTestClient
                        .get()
                        .uri(
                                uriBuilder ->
                                        uriBuilder
                                                .path("/api/runtime/runs/{runId}/stream")
                                                .queryParam("access_token", rawToken)
                                                .build(runId))
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                        .returnResult(RuntimeEventResponse.class)
                        .getResponseBody()
                        .take(1)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        org.assertj.core.api.Assertions.assertThat(events).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(events.get(0).type()).isEqualTo("RUN_STARTED");
    }

    // --- CORS preflight bypass --------------------------------------------------------------
    //
    // Browsers strip `Authorization` from OPTIONS preflights and treat any 4xx as a CORS
    // failure. The JWT filter must short-circuit OPTIONS, and Spring Security must permitAll
    // OPTIONS so the CorsWebFilter (ordered ahead) actually answers the preflight.
    //
    // The tests use absolute URIs (http://server-under-test) because Spring's
    // {@code CorsUtils#isSameOrigin} asserts the request URI has a scheme/host. WebTestClient
    // bound to the application context (via {@code HttpHandlerConnector}) otherwise builds a
    // {@code MockServerHttpRequest} with a relative URI, which would trip the assertion and
    // make every CORS request log "origin is malformed". Real browsers always provide a Host
    // header so this is only a test-environment concern.

    private static final String TEST_HOST = "http://runtime-server.test";

    @Test
    void optionsPreflight_forStartRun_withoutToken_returns200WithAllowOrigin() {
        webTestClient
                .options()
                .uri(TEST_HOST + "/api/runtime/runs")
                .header("Origin", "http://localhost:4202")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "http://localhost:4202")
                .expectHeader()
                .valueMatches("Access-Control-Allow-Methods", ".*POST.*")
                .expectHeader()
                .valueMatches("Access-Control-Allow-Headers", "(?i).*Authorization.*");
    }

    @Test
    void optionsPreflight_forListWorkflows_fromRuntimeUi_returns200() {
        webTestClient
                .options()
                .uri(TEST_HOST + "/api/runtime/workflows")
                .header("Origin", "http://localhost:4202")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "http://localhost:4202");
    }

    @Test
    void optionsPreflight_forSseStream_fromRuntimeUi_returns200() {
        webTestClient
                .options()
                .uri(TEST_HOST + "/api/runtime/runs/" + UUID.randomUUID() + "/stream")
                .header("Origin", "http://localhost:4202")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "http://localhost:4202");
    }

    @Test
    void optionsPreflight_fromAuthUi_returns200() {
        webTestClient
                .options()
                .uri(TEST_HOST + "/api/runtime/workflows")
                .header("Origin", "http://localhost:4201")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "http://localhost:4201");
    }

    @Test
    void optionsPreflight_fromCryptobotUi_returns200() {
        webTestClient
                .options()
                .uri(TEST_HOST + "/api/runtime/workflows")
                .header("Origin", "http://localhost:4203")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "http://localhost:4203");
    }

    @Test
    void postRun_withoutToken_stillReturns401EvenAfterPreflightFix() {
        // Belt-and-braces: real (non-preflight) requests must keep returning 401 without a JWT.
        webTestClient
                .post()
                .uri(TEST_HOST + "/api/runtime/runs")
                .header("Origin", "http://localhost:4202")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"workflowId\":\"demo.no-llm.workflow\",\"input\":\"hello\"}")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void getWorkflows_withoutToken_stillReturns401EvenAfterPreflightFix() {
        webTestClient
                .get()
                .uri(TEST_HOST + "/api/runtime/workflows")
                .header("Origin", "http://localhost:4202")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    private UUID startNoLlmRun(String authority) {
        return webTestClient
                .post()
                .uri("/api/runtime/runs")
                .header("Authorization", RuntimeTestJwt.bearer(List.of(authority)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"workflowId\":\"demo.no-llm.workflow\",\"input\":\"hello\",\"correlationId\":\"sec-sse-helper\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(RunResponse.class)
                .returnResult()
                .getResponseBody()
                .runId();
    }
}
