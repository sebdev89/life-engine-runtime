package io.lifeengine.runtime.prompts;

import io.lifeengine.runtime.api.SecretRedactor;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, versioned prompt template registered in the {@link PromptTemplateRegistry}.
 *
 * <p>{@code systemMessage} is the raw text fed to an LLM as the {@code system} message.
 * {@code sanitizedPreview} is a short, secret-redacted, single-line excerpt safe for
 * surfacing in events / API responses without leaking the full prompt body. If a caller
 * does not provide one, a preview is derived automatically from {@code systemMessage}.
 */
public record PromptTemplate(
        String id, String version, String systemMessage, String sanitizedPreview) {

    private static final int DEFAULT_PREVIEW_MAX = 240;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public PromptTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(systemMessage, "systemMessage");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (systemMessage.isBlank()) {
            throw new IllegalArgumentException("systemMessage must not be blank");
        }
        if (sanitizedPreview == null || sanitizedPreview.isBlank()) {
            sanitizedPreview = derivePreview(systemMessage);
        }
    }

    /** Convenience factory: derive {@code sanitizedPreview} from {@code systemMessage}. */
    public static PromptTemplate of(String id, String version, String systemMessage) {
        return new PromptTemplate(id, version, systemMessage, null);
    }

    private static String derivePreview(String systemMessage) {
        String redacted = SecretRedactor.redact(systemMessage);
        String collapsed = WHITESPACE.matcher(redacted).replaceAll(" ").trim();
        if (collapsed.length() <= DEFAULT_PREVIEW_MAX) {
            return collapsed;
        }
        return collapsed.substring(0, DEFAULT_PREVIEW_MAX) + "…";
    }
}
