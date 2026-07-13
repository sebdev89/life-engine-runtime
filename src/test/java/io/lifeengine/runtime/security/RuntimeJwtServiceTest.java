package io.lifeengine.runtime.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeJwtServiceTest {

    private static final RuntimeSecurityProperties SECURITY_PROPS = new RuntimeSecurityProperties(true, true);

    @Test
    void rejectsShortOrMissingSecret_whenJwksNotConfigured() {
        JwksPublicKeyProvider unconfigured = new JwksPublicKeyProvider(new RuntimeJwksProperties(""), new ObjectMapper());

        assertThatThrownBy(() -> new RuntimeJwtService(new RuntimeJwtProperties(""), SECURITY_PROPS, unconfigured))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 UTF-8 bytes");

        assertThatThrownBy(
                        () -> new RuntimeJwtService(
                                new RuntimeJwtProperties("too-short"), SECURITY_PROPS, unconfigured))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsMissingSecret_whenJwksIsConfigured() {
        // KAN-32 follow-up: this service only ever verifies (never signs) tokens, so once
        // AUTH_JWKS_URI is set there's no reason JWT_SECRET should still be mandatory.
        JwksPublicKeyProvider configured =
                new JwksPublicKeyProvider(
                        new RuntimeJwksProperties("http://auth:8081/.well-known/jwks.json"), new ObjectMapper());

        assertThatCode(() -> new RuntimeJwtService(new RuntimeJwtProperties(""), SECURITY_PROPS, configured))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnyHs256Token_whenNoRealSecretConfigured_evenIfSignedWithAKnownWeakKey() {
        // The critical regression this guards: HS256 verification must be fully disabled when
        // there's no real secret, not silently accepted against some fixed fallback key — a
        // fallback key sitting in source control would let anyone forge a token that verifies.
        JwksPublicKeyProvider configured =
                new JwksPublicKeyProvider(
                        new RuntimeJwksProperties("http://auth:8081/.well-known/jwks.json"), new ObjectMapper());
        var service = new RuntimeJwtService(new RuntimeJwtProperties(""), SECURITY_PROPS, configured);

        // Forge an HS256 token the way an attacker who read the old dummy-key source would.
        String forged =
                Jwts.builder()
                        .subject(UUID.randomUUID().toString())
                        .claim("email", "attacker@example.com")
                        .claim("role", "ADMIN")
                        .signWith(
                                Keys.hmacShaKeyFor(
                                        "runtime-jwks-mode-hmac-fallback-dummy-key!!"
                                                .getBytes(StandardCharsets.UTF_8)))
                        .compact();

        var outcome = service.parseToken(forged);

        assertThat(outcome.principal()).isEmpty();
        assertThat(outcome.failureReason()).contains("hs256_disabled");
    }

    @Test
    void acceptsValidSecret_regardlessOfJwksConfig() {
        JwksPublicKeyProvider unconfigured = new JwksPublicKeyProvider(new RuntimeJwksProperties(""), new ObjectMapper());
        String validSecret = "a-perfectly-valid-32-plus-byte-secret!!";

        assertThatCode(
                        () -> new RuntimeJwtService(new RuntimeJwtProperties(validSecret), SECURITY_PROPS, unconfigured))
                .doesNotThrowAnyException();
    }
}
