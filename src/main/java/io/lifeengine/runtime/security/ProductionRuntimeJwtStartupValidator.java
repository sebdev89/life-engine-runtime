package io.lifeengine.runtime.security;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Rejects production boot when {@code JWT_SECRET} is missing or still the local dev default
 * ({@code KAN-34} — Runtime half of the fail-fast gap).
 */
@Component
@Profile("!test")
public class ProductionRuntimeJwtStartupValidator {

    static final String DEV_DEFAULT_SECRET =
            "local-dev-jwt-hs512-secret-minimum-32-chars-long-xx";

    private final Environment environment;
    private final RuntimeJwtProperties jwtProperties;

    public ProductionRuntimeJwtStartupValidator(
            Environment environment, RuntimeJwtProperties jwtProperties) {
        this.environment = environment;
        this.jwtProperties = jwtProperties;
    }

    @PostConstruct
    void validateProductionJwtSecret() {
        if (!isProductionRuntime()) {
            return;
        }
        String secret = jwtProperties.secret() == null ? "" : jwtProperties.secret().trim();
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException(
                    "lifeengine.security.jwt.secret (JWT_SECRET) is required in production");
        }
        if (DEV_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "JWT_SECRET must not use the local dev default in production");
        }
    }

    private boolean isProductionRuntime() {
        if ("prod".equalsIgnoreCase(environment.getProperty("APP_ENV", "").trim())) {
            return true;
        }
        return environment.acceptsProfiles(Profiles.of("prod"));
    }
}
