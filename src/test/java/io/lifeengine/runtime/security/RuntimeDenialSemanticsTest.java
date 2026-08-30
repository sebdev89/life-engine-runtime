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
 *
 * <p><b>Por qué cada aserción exige además el CUERPO.</b> El defecto venía de handlers que hacían
 * {@code setStatusCode(...)} seguido de {@code setComplete()}, sin escribir nada. Con esa forma el
 * estado nuevo no llegaba al cable y salía 200 vacío. Exigir sólo el código de estado no alcanza
 * como red: este harness renderizaba 403 correctamente incluso ANTES del arreglo, así que un test
 * que sólo mirara el status pasaba con el bug presente. La aserción sobre {@code $.code} sí falla
 * si alguien vuelve a {@code setComplete()}, porque ese camino no produce cuerpo.
 *
 * <p>Dicho con todas las letras: estos tests protegen contra una regresión de la implementación,
 * NO reproducen el fallo original. La única verificación que lo detecta es una petición real contra
 * el servicio desplegado.
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
    @DisplayName("OPERATOR real sin RUNTIME_ADMIN contra /actuator/metrics → 403 CON cuerpo")
    void operatorTokenWithoutAdminAuthority_returns403() {
        webTestClient
                .get()
                .uri("/actuator/metrics")
                .header("Authorization", RuntimeTestJwt.bearerForPlatformRole("OPERATOR", OPERATOR_AUTHORITIES))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("forbidden");
    }

    @Test
    @DisplayName("USER real sin RUNTIME_OPERATOR contra POST /api/runtime/runs → 403 CON cuerpo")
    void userTokenWithoutOperatorAuthority_returns403() {
        webTestClient
                .post()
                .uri("/api/runtime/runs")
                .header("Authorization", RuntimeTestJwt.bearerForPlatformRole("USER", USER_AUTHORITIES))
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("forbidden");
    }

    @Test
    @DisplayName("Ruta sin regla explícita cae en denyAll → 403 CON cuerpo")
    void unmatchedPath_returns403() {
        webTestClient
                .get()
                .uri("/no-existe")
                .header("Authorization", RuntimeTestJwt.bearerForPlatformRole("OPERATOR", OPERATOR_AUTHORITIES))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("forbidden");
    }

    @Test
    @DisplayName("Sin token → 401 CON cuerpo")
    void noToken_returns401() {
        webTestClient
                .get()
                .uri("/actuator/metrics")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("unauthorized");
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
