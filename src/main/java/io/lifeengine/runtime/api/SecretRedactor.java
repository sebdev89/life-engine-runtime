package io.lifeengine.runtime.api;

import java.util.regex.Pattern;

/** Redacts common secret patterns before persistence or API exposure. */
public final class SecretRedactor {

    private static final Pattern BEARER = Pattern.compile("Bearer\\s+[A-Za-z0-9._-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern API_KEY =
            Pattern.compile("(api[_-]?key\\s*[:=]\\s*)[A-Za-z0-9._-]+", Pattern.CASE_INSENSITIVE);

    private SecretRedactor() {}

    public static String redact(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String out = BEARER.matcher(value).replaceAll("Bearer [REDACTED]");
        out = API_KEY.matcher(out).replaceAll("$1[REDACTED]");
        return out;
    }
}
