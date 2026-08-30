package io.lifeengine.runtime.security;

import java.util.List;
import java.util.UUID;

/**
 * Llamador autenticado tras validar el JWT.
 *
 * <p>{@code tenantKey} sale del claim {@code tenant} que Auth emite en los tokens de usuario
 * (V57). Es {@code null} en los tokens de servicio, que todavía no lo llevan — eso es W2, con
 * {@code act.tenant}. Runtime no decide nada con este valor: lo registra en la corrida.
 */
public record RuntimePrincipal(
        UUID userId, String email, String primaryRole, List<String> authorities, String tenantKey) {

    /** Compatibilidad con los call sites anteriores al claim de tenant (tests, sobre todo). */
    public RuntimePrincipal(UUID userId, String email, String primaryRole, List<String> authorities) {
        this(userId, email, primaryRole, authorities, null);
    }
}
