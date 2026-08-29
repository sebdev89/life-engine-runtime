package io.lifeengine.runtime.security;

import io.lifeengine.runtime.app.RuntimeApplication;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * KAN-264 — el contrato de denegación, con tokens de la forma EXACTA que emite life-engine-auth.
 *
 * <p>Los casos de {@link RuntimeSecurityWebFluxTest} usan tokens sintéticos con una sola authority
 * {@code RUNTIME_*} y sin claim {@code role} realista. Estos, en cambio, reproducen lo que un humano
 * recibe hoy de Auth: claim {@code role} de plataforma más la lista completa de authorities, incluida
 * {@code ROLE_USER}.
 *
 * <p>La distinción importa porque en UAT, con un token así, una denegación de autorización se
 * observó como <b>200 con cuerpo vacío</b> en lugar de 403 — indistinguible de un éxito sin datos
 * para cualquier cliente. Estos tests fijan el contrato:
 *
 * <ul>
 *   <li>sin autenticación → 401
 *   <li>autenticado con la authority correcta → respuesta normal
 *   <li>autenticado sin la authority → <b>403</b>, nunca 200
 * </ul>
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "lifeengine.runtime.security.enabled=true",
            "lifeengine.runtime.security.derive-runtime-authorities-from-role=true",
            "lifeengine.security.jwt.secret=" + RuntimeTestJwt.TEST_SECRET,
            "management.endpoints.web.exposure.include=health,info,metrics",
            "management.tracing.enabled=true",
            "management.otlp.tracing.enabled=true"
        })
@DisplayName("KAN-264 — una denegación de autorización nunca puede verse como 200")
class RuntimeDenialSemanticsTest {

    /** La forma real del token de un OPERATOR emitido por Auth tras V59. */
    private static final List<String> OPERATOR_AUTHORITIES =
            List.of(
                    "BUSINESS_CHAT_OPERATOR",
                    "BUSINESS_CHAT_VIEWER",
                    "ROLE_USER",
                    "RUNTIME_OPERATOR",
                    "RUNTIME_VIEWER");

    /** La forma real del token de un USER: sin ninguna authority RUNTIME_*. */
    private static final List<String> USER_AUTHORITIES = List.of("BUSINESS_CHAT_ADMIN", "ROLE_USER");

    @Autowired private WebTestClient webTestClient;

    @Test
    @DisplayName("OPERATOR real sin RUNTIME_ADMIN contra /actuator/metrics → 403, no 200 vacío")
    void operatorTokenWithoutAdminAuthority_returns403() {
        webTestClient
                .get()
                .uri("/actuator/metrics")
                .header("Authorization", RuntimeTestJwt.bearerForPlatformRole("OPERATOR", OPERATOR_AUTHORITIES))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @DisplayName("USER real sin RUNTIME_OPERATOR contra POST /api/runtime/runs → 403, no 200 vacío")
    void userTokenWithoutOperatorAuthority_returns403() {
        webTestClient
                .post()
                .uri("/api/runtime/runs")
                .header("Authorization", RuntimeTestJwt.bearerForPlatformRole("USER", USER_AUTHORITIES))
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @DisplayName("Sin token → 401")
    void noToken_returns401() {
        webTestClient.get().uri("/actuator/metrics").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("ADMIN real con RUNTIME_ADMIN → 200 con cuerpo")
    void adminTokenWithAdminAuthority_returns200WithBody() {
        webTestClient
                .get()
                .uri("/actuator/metrics")
                .header(
                        "Authorization",
                        RuntimeTestJwt.bearerForPlatformRole(
                                "ADMIN", List.of("ROLE_ADMIN", "ROLE_USER", "RUNTIME_ADMIN", "RUNTIME_OPERATOR", "RUNTIME_VIEWER")))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.names")
                .exists();
    }
}
