package io.lifeengine.runtime.prompts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PromptTemplateTest {

    @Test
    void of_derivesSanitizedPreviewFromSystemMessage() {
        String body =
                """
                You are a careful analyst.
                Use lower-case 0.0-1.0 numeric for confidence.
                """
                        .strip();

        PromptTemplate template = PromptTemplate.of("crypto.market-review.analyst", "v1", body);

        assertThat(template.id()).isEqualTo("crypto.market-review.analyst");
        assertThat(template.version()).isEqualTo("v1");
        assertThat(template.systemMessage()).isEqualTo(body);
        assertThat(template.sanitizedPreview())
                .doesNotContain("\n")
                .startsWith("You are a careful analyst.")
                .contains("Use lower-case 0.0-1.0 numeric for confidence.");
    }

    @Test
    void of_truncatesSanitizedPreviewWhenLong() {
        StringBuilder body = new StringBuilder("HEAD ");
        while (body.length() < 600) {
            body.append("xxxxxxxxxx ");
        }

        PromptTemplate template = PromptTemplate.of("p.long", "v1", body.toString());

        assertThat(template.sanitizedPreview()).startsWith("HEAD").endsWith("…").hasSize(241);
    }

    @Test
    void of_sanitizedPreviewRedactsBearerSecrets() {
        String body = "You may use header Authorization: Bearer abcdef123 for downstream calls.";

        PromptTemplate template = PromptTemplate.of("p.with-secret", "v1", body);

        assertThat(template.sanitizedPreview()).contains("Bearer [REDACTED]");
        assertThat(template.sanitizedPreview()).doesNotContain("abcdef123");
    }

    @Test
    void canonicalConstructor_acceptsExplicitSanitizedPreview() {
        PromptTemplate template =
                new PromptTemplate("p.custom", "v2", "long system body...", "custom preview");

        assertThat(template.sanitizedPreview()).isEqualTo("custom preview");
    }

    @Test
    void canonicalConstructor_blankSanitizedPreviewIsAutoDerived() {
        PromptTemplate template = new PromptTemplate("p.blank", "v1", "Hello world", "   ");

        assertThat(template.sanitizedPreview()).isEqualTo("Hello world");
    }

    @Test
    void blankIdRejected() {
        assertThatThrownBy(() -> PromptTemplate.of("", "v1", "body"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void blankVersionRejected() {
        assertThatThrownBy(() -> PromptTemplate.of("p.id", "", "body"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void blankSystemMessageRejected() {
        assertThatThrownBy(() -> PromptTemplate.of("p.id", "v1", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("systemMessage");
    }
}
