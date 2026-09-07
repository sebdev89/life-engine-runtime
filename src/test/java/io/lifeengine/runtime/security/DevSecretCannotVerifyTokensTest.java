package io.lifeengine.runtime.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Un default compilado en el binario no puede habilitar verificación de firma.
 *
 * <p><b>El agujero, tal como estaba.</b> {@code application.yml} traía
 * {@code secret: ${JWT_SECRET:local-dev-jwt-hs512-secret-minimum-32-chars-long-xx}}. Ese valor
 * mide 51 bytes, o sea más de los 32 que {@link RuntimeJwtService} exige para aceptar una clave
 * HMAC, así que la verificación HS256 quedaba <b>activa en todos los ambientes</b> con una clave
 * que cualquiera con acceso al repositorio puede leer.
 *
 * <p>El guard que existía no lo tapaba: sólo deshabilita HS256 cuando el secreto es <em>demasiado
 * corto</em> para ser real. Un default largo se ve idéntico a un secreto de verdad.
 *
 * <p>No es teórico. Con ese default se firmó un token {@code role=ADMIN} y se operó la API del
 * Runtime en el cluster K3s durante KAN-299; después del arreglo de configuración de KAN-303
 * —poner el JWKS— el mismo token seguía devolviendo {@code 201 Created}. Esto es lo que faltaba.
 *
 * <p>Los otros casos negativos —issuer, audiencia, expiración, kid, firma RS256 inválida— ya están
 * cubiertos en {@link ServiceTokenGuardTest}. Este test cubre el que faltaba: el que no es un
 * token mal formado sino un token <em>perfectamente válido</em>, firmado con una clave pública.
 */
class DevSecretCannotVerifyTokensTest {

    /** El valor exacto que vivía en application.yml. Está en la historia del repo: no es secreto. */
    private static final String SECRETO_DE_DESARROLLO =
            "local-dev-jwt-hs512-secret-minimum-32-chars-long-xx";

    @Test
    @DisplayName("con JWKS configurado y sin JWT_SECRET, un token firmado con el default histórico se RECHAZA")
    void historicDevSecretIsRejectedWhenJwksIsConfigured() {
        RuntimeJwtService service = serviceWith("", true);

        var outcome = service.parseAuthorizationHeader("Bearer " + tokenFirmadoCon(SECRETO_DE_DESARROLLO));

        assertThat(outcome.principal())
                .as(
                        "el token está bien formado y bien firmado — con una clave que está en el"
                            + " repositorio. Aceptarlo es aceptar que cualquiera se firme un admin.")
                .isEmpty();
    }

    @Test
    @DisplayName("sin JWKS y sin secreto, el servicio NO arranca")
    void refusesToStartWithNeitherSecretNorJwks() {
        // Falla cerrado. Un Runtime que no puede validar nada no debería aceptar nada, y arrancar
        // igual sería peor que no arrancar: quedaría sirviendo con una cadena de seguridad que no
        // puede decidir.
        assertThatThrownBy(() -> serviceWith("", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 UTF-8 bytes");
    }

    @Test
    @DisplayName("un JWT_SECRET explícito de verdad sigue funcionando")
    void anExplicitSecretStillWorks() {
        // El arreglo no rompe el camino legítimo: un ambiente que EXPORTA su secreto sigue
        // validando HS256. Sin este caso, el test anterior también pasaría con la verificación
        // HS256 rota del todo, que no es lo que se quiere.
        String propio = "un-secreto-de-verdad-de-al-menos-32-bytes-xx";
        RuntimeJwtService service = serviceWith(propio, false);

        var outcome = service.parseAuthorizationHeader("Bearer " + tokenFirmadoCon(propio));

        assertThat(outcome.principal()).isPresent();
        assertThat(outcome.principal().orElseThrow().tenantKey()).isEqualTo("chizzini");
    }

    @Test
    @DisplayName("application.yml NO trae un secreto por default — el guard del archivo, no del código")
    void sharedConfigShipsNoUsableSecretDefault() throws Exception {
        // Los tres tests de arriba prueban el SERVICIO, y el servicio ya se comportaba bien: con
        // secreto vacío hace lo correcto. El defecto real no estaba ahí, estaba en el YAML, que le
        // pasaba un default de 51 bytes y hacía que ese camino nunca se ejercitara.
        //
        // Este test mira el archivo. Es el único que falla si alguien vuelve a escribir un default.
        String yaml =
                new String(
                        getClass().getResourceAsStream("/application.yml").readAllBytes(),
                        StandardCharsets.UTF_8);

        var secreto =
                yaml.lines()
                        .map(String::trim)
                        .filter(l -> l.startsWith("secret:"))
                        .filter(l -> l.contains("JWT_SECRET"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "no se encontró la propiedad del secreto en"
                                                    + " application.yml; si se movió, este guard hay"
                                                    + " que moverlo con ella"));

        assertThat(secreto)
                .as(
                        "un default en el archivo COMÚN aplica a todos los ambientes, y uno de más"
                            + " de 32 bytes deja la verificación HS256 activa con una clave que está"
                            + " en el repositorio. El secreto de desarrollo va en"
                            + " application-local.yml.")
                .isEqualTo("secret: ${JWT_SECRET:}");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private static RuntimeJwtService serviceWith(String secret, boolean jwksConfigurado) {
        var jwks =
                new JwksPublicKeyProvider(
                        new RuntimeJwksProperties(
                                jwksConfigurado
                                        ? "http://auth-inexistente.local/.well-known/jwks.json"
                                        : ""),
                        new ObjectMapper());
        var guard =
                new ServiceTokenGuard(
                        new ServiceTokenProperties(
                                "life-engine-auth",
                                "runtime",
                                List.of("service:business-chat"),
                                List.of("business-chat"),
                                "RUNTIME_OPERATOR"));
        return new RuntimeJwtService(
                new RuntimeJwtProperties(secret),
                new RuntimeSecurityProperties(true, true),
                jwks,
                guard);
    }

    private static String tokenFirmadoCon(String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "intruso@ejemplo.local")
                .claim("role", "ADMIN")
                .claim("tenant", "chizzini")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
    }
}
