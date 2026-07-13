package io.lifeengine.runtime.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    void acceptsValidSecret_regardlessOfJwksConfig() {
        JwksPublicKeyProvider unconfigured = new JwksPublicKeyProvider(new RuntimeJwksProperties(""), new ObjectMapper());
        String validSecret = "a-perfectly-valid-32-plus-byte-secret!!";

        assertThatCode(
                        () -> new RuntimeJwtService(new RuntimeJwtProperties(validSecret), SECURITY_PROPS, unconfigured))
                .doesNotThrowAnyException();
    }
}
