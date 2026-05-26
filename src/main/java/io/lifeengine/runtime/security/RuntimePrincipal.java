package io.lifeengine.runtime.security;

import java.util.List;
import java.util.UUID;

/** Authenticated caller after JWT validation (compatible with life-engine BO tokens). */
public record RuntimePrincipal(
        UUID userId, String email, String primaryRole, List<String> authorities) {}
