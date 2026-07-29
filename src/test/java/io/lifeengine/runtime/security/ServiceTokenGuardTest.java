package io.lifeengine.runtime.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validación de tokens service-to-service en Runtime (KAN-173).
 *
 * <p>Casi todo acá verifica RECHAZOS. Un validador que acepta de más se ve idéntico a uno correcto
 * hasta que alguien presenta el token que no debía funcionar.
 */
class ServiceTokenGuardTest {

    private static final String ISSUER = "life-engine-auth-test";
    private static final String KID = "test-kid";
    private static final String SECRET_HISTORICO =
            "el-jwt-secret-global-historico-de-al-menos-32-bytes";

    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;
    private static RSAPrivateKey otraPrivateKey;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey = (RSAPublicKey) pair.getPublic();
        otraPrivateKey = (RSAPrivateKey) gen.generateKeyPair().getPrivate();
    }

    private static ServiceTokenGuard guard() {
        return new ServiceTokenGuard(new ServiceTokenProperties(
                ISSUER, "runtime", List.of("service:business-chat"), List.of("business-chat"), "RUNTIME_OPERATOR"));
    }

    /** Builder de tokens S2S; cada test cambia solo lo que quiere romper. */
    private static class TokenBuilder {
        String issuer = ISSUER;
        String audience = "runtime";
        boolean withAudience = true;
        String subject = "service:business-chat";
        String clientId = "business-chat";
        String tokenUse = "service";
        List<String> authorities = List.of("RUNTIME_OPERATOR", "RUNTIME_VIEWER");
        Instant exp = Instant.now().plusSeconds(300);
        String kid = KID;
        boolean withKid = true;
        RSAPrivateKey signingKey = privateKey;
        SecretKey hmacKey = null;

        String build() {
            var b = Jwts.builder()
                    .id(UUID.randomUUID().toString())
                    .subject(subject)
                    .claim("token_use", tokenUse)
                    .claim("client_id", clientId)
                    .claim("authorities", authorities)
                    .issuedAt(Date.from(Instant.now()))
                    .expiration(Date.from(exp));
            if (issuer != null) b.issuer(issuer);
            if (withAudience && audience != null) b.audience().add(audience).and();
            if (hmacKey != null) {
                return b.signWith(hmacKey).compact();
            }
            if (withKid) b.header().keyId(kid).and();
            return b.signWith(signingKey).compact();
        }
    }

    /** Parsea y valida como lo hace RuntimeJwtService: firma primero, contrato después. */
    private static Optional<String> validate(String token, String alg) {
        var claims = alg.equals("RS256")
                ? Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload()
                : Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor(SECRET_HISTORICO.getBytes(StandardCharsets.UTF_8)))
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
        return guard().validate(claims, alg, RuntimeJwtService.peekKid(token));
    }

    // ── 1. Camino feliz ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. token S2S RS256 válido para aud=runtime → aceptado")
    void tokenValido() {
        assertThat(validate(new TokenBuilder().build(), "RS256")).isEmpty();
    }

    @Test
    @DisplayName("el principal derivado es estable y no inventa un usuario")
    void principalDeServicio() {
        var claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(new TokenBuilder().build())
                .getPayload();
        var p1 = ServiceTokenGuard.toPrincipal(claims);
        var p2 = ServiceTokenGuard.toPrincipal(claims);

        assertThat(p1.userId()).isEqualTo(p2.userId()); // determinístico → correlacionable en logs
        assertThat(p1.primaryRole()).isEqualTo("SERVICE");
        assertThat(p1.email()).isEqualTo("service:business-chat");
        assertThat(p1.authorities()).contains("RUNTIME_OPERATOR");
    }

    // ── 2-14. Rechazos ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("2. token de la audiencia contraria (aud=rag) → rechazado")
    void audienciaContraria() {
        var t = new TokenBuilder();
        t.audience = "rag";
        assertThat(validate(t.build(), "RS256")).contains("s2s_audience_mismatch");
    }

    @Test
    @DisplayName("2b. aud=[runtime,rag] → rechazado: se exige el conjunto EXACTO, no 'contiene'")
    void audienciaMultiple() {
        // Con `contains` este token pasaría en Runtime Y en RAG: sería el token universal que todo
        // el diseño evita. La equivalencia exacta obliga al emisor a acotarlo a un solo destino.
        String token = Jwts.builder()
                .subject("service:business-chat")
                .issuer(ISSUER)
                .audience()
                .add("runtime")
                .add("rag")
                .and()
                .claim("token_use", "service")
                .claim("client_id", "business-chat")
                .claim("authorities", List.of("RUNTIME_OPERATOR"))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .header()
                .keyId(KID)
                .and()
                .signWith(privateKey)
                .compact();
        assertThat(validate(token, "RS256")).contains("s2s_audience_mismatch");
    }

    @Test
    @DisplayName("3. aud ausente → rechazado")
    void sinAudiencia() {
        var t = new TokenBuilder();
        t.withAudience = false;
        assertThat(validate(t.build(), "RS256")).contains("s2s_missing_audience");
    }

    @Test
    @DisplayName("4. iss incorrecto → rechazado")
    void issuerIncorrecto() {
        var t = new TokenBuilder();
        t.issuer = "https://auth-de-otro-ambiente";
        assertThat(validate(t.build(), "RS256")).contains("s2s_issuer_mismatch");
    }

    @Test
    @DisplayName("5. iss ausente → rechazado")
    void issuerAusente() {
        var t = new TokenBuilder();
        t.issuer = null;
        assertThat(validate(t.build(), "RS256")).contains("s2s_issuer_mismatch");
    }

    @Test
    @DisplayName("5b. issuer esperado sin configurar → se rechaza todo (fail-closed)")
    void issuerSinConfigurar() {
        var sinIssuer = new ServiceTokenGuard(new ServiceTokenProperties(
                "", "runtime", List.of("service:business-chat"), List.of("business-chat"), "RUNTIME_OPERATOR"));
        var claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(new TokenBuilder().build())
                .getPayload();
        assertThat(sinIssuer.validate(claims, "RS256", KID)).contains("s2s_issuer_not_configured");
    }

    @Test
    @DisplayName("7. token S2S HS256 firmado con el secreto histórico → RECHAZADO")
    void hs256ConSecretoHistoricoRechazado() {
        // El test que define el ticket. Este token está PERFECTAMENTE firmado con el JWT_SECRET
        // que hoy comparten los cinco servicios. Si Runtime lo aceptara, ese secreto seguiría
        // alcanzando para hablar como Business Chat y toda la migración sería decorativa.
        var t = new TokenBuilder();
        t.hmacKey = Keys.hmacShaKeyFor(SECRET_HISTORICO.getBytes(StandardCharsets.UTF_8));
        assertThat(validate(t.build(), "HS256")).contains("s2s_alg_not_rs256");
    }

    @Test
    @DisplayName("8. firma RS256 inválida → rechazada al verificar, nunca llega al contrato")
    void firmaInvalida() {
        var t = new TokenBuilder();
        t.signingKey = otraPrivateKey;
        // La firma se verifica ANTES del contrato: un token mal firmado no debe siquiera evaluarse.
        org.junit.jupiter.api.Assertions.assertThrows(
                io.jsonwebtoken.security.SignatureException.class,
                () -> Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(t.build()));
    }

    @Test
    @DisplayName("9. kid ausente → rechazado")
    void sinKid() {
        var t = new TokenBuilder();
        t.withKid = false;
        var claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(t.build())
                .getPayload();
        assertThat(guard().validate(claims, "RS256", "")).contains("s2s_missing_kid");
    }

    @Test
    @DisplayName("11. token vencido → rechazado al verificar")
    void vencido() {
        var t = new TokenBuilder();
        t.exp = Instant.now().minusSeconds(60);
        org.junit.jupiter.api.Assertions.assertThrows(
                io.jsonwebtoken.ExpiredJwtException.class,
                () -> Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(t.build()));
    }

    @Test
    @DisplayName("12. sub incorrecto → rechazado")
    void subIncorrecto() {
        var t = new TokenBuilder();
        t.subject = "service:un-servicio-que-nadie-autorizo";
        assertThat(validate(t.build(), "RS256")).contains("s2s_subject_not_allowed");
    }

    @Test
    @DisplayName("13. client_id incorrecto → rechazado")
    void clientIdIncorrecto() {
        var t = new TokenBuilder();
        t.clientId = "otro-servicio";
        assertThat(validate(t.build(), "RS256")).contains("s2s_client_id_not_allowed");
    }

    @Test
    @DisplayName("14. authority insuficiente → rechazado")
    void authorityInsuficiente() {
        var t = new TokenBuilder();
        t.authorities = List.of("RUNTIME_VIEWER"); // sin OPERATOR
        assertThat(validate(t.build(), "RS256")).contains("s2s_insufficient_authority");
    }

    // ── 6 y 15. La frontera entre los dos contratos ───────────────────────────────────────

    @Test
    @DisplayName("6. token_use distinto de 'service' → NO se trata como token de servicio")
    void tokenUseIncorrecto() {
        var t = new TokenBuilder();
        t.tokenUse = "access";
        var claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(t.build())
                .getPayload();
        assertThat(ServiceTokenGuard.isServiceToken(claims)).isFalse();
    }

    @Test
    @DisplayName("15. token de usuario sin iss/aud NO es tratado como identidad de servicio")
    void tokenDeUsuarioNoEsServicio() {
        // Los tokens de usuario nunca llevaron iss ni aud. Si la AUSENCIA de token_use se
        // interpretara como identidad de servicio, cualquier sesión válida entraría por el camino
        // S2S. Y si se les exigiera audiencia, se romperían todas las sesiones abiertas.
        String tokenUsuario = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "persona@life-engine.app")
                .claim("role", "ADMIN")
                .claim("authorities", List.of("RUNTIME_OPERATOR"))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(900)))
                .header()
                .keyId(KID)
                .and()
                .signWith(privateKey)
                .compact();

        var claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(tokenUsuario)
                .getPayload();

        assertThat(ServiceTokenGuard.isServiceToken(claims)).isFalse();
    }
}
