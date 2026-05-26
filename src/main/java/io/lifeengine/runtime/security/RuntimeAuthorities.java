package io.lifeengine.runtime.security;

/** Platform JWT authority codes for life-engine-runtime (stored in JWT {@code authorities} claim). */
public final class RuntimeAuthorities {

    public static final String VIEWER = "RUNTIME_VIEWER";
    public static final String OPERATOR = "RUNTIME_OPERATOR";
    public static final String ADMIN = "RUNTIME_ADMIN";

    private RuntimeAuthorities() {}
}
