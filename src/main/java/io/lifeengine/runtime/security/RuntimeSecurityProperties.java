package io.lifeengine.runtime.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Runtime API security toggles (JWT validation aligned with life-engine
 * {@code lifeengine.security.jwt}).
 *
 * <p>{@code deriveRuntimeAuthoritiesFromRole} is a <strong>Phase-1 bridge</strong>: while
 * {@code life-engine-auth} has not yet seeded {@code RUNTIME_VIEWER} / {@code RUNTIME_OPERATOR} /
 * {@code RUNTIME_ADMIN} permission rows (see {@code life-engine-auth}
 * {@code db/migration/V49__auth_rbac.sql}), tokens issued by the platform login only carry
 * {@code ROLE_*} authorities. To unblock the runtime happy path without introducing a second auth
 * system, we mint local {@code RUNTIME_*} authorities from the JWT {@code role} claim
 * (and from {@code ROLE_ADMIN}). This MUST be turned off — and a proper Flyway migration shipped
 * in {@code life-engine-auth} — before the contract is considered final.
 *
 * <p>Tracked in {@code docs/extraction/local-runbook.md} (troubleshooting row) and
 * {@code life-engine-runtime/docs/operations/runtime-rbac-seed.sql} (seed template).
 */
@ConfigurationProperties(prefix = "lifeengine.runtime.security")
public record RuntimeSecurityProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean deriveRuntimeAuthoritiesFromRole) {}
