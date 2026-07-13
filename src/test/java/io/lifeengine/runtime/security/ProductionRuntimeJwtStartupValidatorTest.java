package io.lifeengine.runtime.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

class ProductionRuntimeJwtStartupValidatorTest {

    @Test
    void skipsValidationWhenNotProduction() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("APP_ENV", "")).thenReturn("");
        when(environment.acceptsProfiles(Profiles.of("prod"))).thenReturn(false);

        var validator =
                new ProductionRuntimeJwtStartupValidator(
                        environment,
                        new RuntimeJwtProperties(ProductionRuntimeJwtStartupValidator.DEV_DEFAULT_SECRET));

        assertThatCode(validator::validateProductionJwtSecret).doesNotThrowAnyException();
    }

    @Test
    void rejectsEmptySecretInProduction() {
        Environment environment = prodEnvironment();

        var validator = new ProductionRuntimeJwtStartupValidator(environment, new RuntimeJwtProperties("  "));

        assertThatThrownBy(validator::validateProductionJwtSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void rejectsDevDefaultSecretInProduction() {
        Environment environment = prodEnvironment();

        var validator =
                new ProductionRuntimeJwtStartupValidator(
                        environment,
                        new RuntimeJwtProperties(ProductionRuntimeJwtStartupValidator.DEV_DEFAULT_SECRET));

        assertThatThrownBy(validator::validateProductionJwtSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local dev default");
    }

    @Test
    void acceptsCustomSecretInProduction() {
        Environment environment = prodEnvironment();

        var validator =
                new ProductionRuntimeJwtStartupValidator(
                        environment, new RuntimeJwtProperties("prod-secret-at-least-32-characters-long"));

        assertThatCode(validator::validateProductionJwtSecret).doesNotThrowAnyException();
    }

    private static Environment prodEnvironment() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("APP_ENV", "")).thenReturn("prod");
        when(environment.acceptsProfiles(Profiles.of("prod"))).thenReturn(true);
        return environment;
    }
}
