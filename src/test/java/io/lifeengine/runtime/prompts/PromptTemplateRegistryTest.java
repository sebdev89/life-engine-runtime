package io.lifeengine.runtime.prompts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PromptTemplateRegistryTest {

    @Test
    void require_returnsRegisteredTemplate() {
        PromptTemplateRegistry registry = new PromptTemplateRegistry();
        PromptTemplate template = PromptTemplate.of("crypto.market-review.analyst", "v1", "body");

        registry.register(template);

        PromptTemplate resolved = registry.require("crypto.market-review.analyst", "v1");
        assertThat(resolved).isSameAs(template);
    }

    @Test
    void require_distinctVersionsCoexist() {
        PromptTemplateRegistry registry = new PromptTemplateRegistry();
        PromptTemplate v1 = PromptTemplate.of("p.id", "v1", "body v1");
        PromptTemplate v2 = PromptTemplate.of("p.id", "v2", "body v2");

        registry.register(v1);
        registry.register(v2);

        assertThat(registry.require("p.id", "v1")).isSameAs(v1);
        assertThat(registry.require("p.id", "v2")).isSameAs(v2);
        assertThat(registry.all()).containsExactlyInAnyOrder(v1, v2);
    }

    @Test
    void register_replacesSameIdAndVersion() {
        PromptTemplateRegistry registry = new PromptTemplateRegistry();
        PromptTemplate first = PromptTemplate.of("p.id", "v1", "old body");
        PromptTemplate second = PromptTemplate.of("p.id", "v1", "new body");

        registry.register(first);
        registry.register(second);

        assertThat(registry.require("p.id", "v1")).isSameAs(second);
        assertThat(registry.all()).containsExactly(second);
    }

    @Test
    void require_unknownTemplateThrowsPromptTemplateNotFound() {
        PromptTemplateRegistry registry = new PromptTemplateRegistry();

        assertThatThrownBy(() -> registry.require("missing", "v1"))
                .isInstanceOf(PromptTemplateNotFoundException.class)
                .hasMessageContaining("missing@v1");
    }

    @Test
    void require_unknownVersionForKnownIdThrows() {
        PromptTemplateRegistry registry = new PromptTemplateRegistry();
        registry.register(PromptTemplate.of("p.id", "v1", "body"));

        assertThatThrownBy(() -> registry.require("p.id", "v2"))
                .isInstanceOf(PromptTemplateNotFoundException.class)
                .hasMessageContaining("p.id@v2");
    }

    @Test
    void register_nullTemplateRejected() {
        PromptTemplateRegistry registry = new PromptTemplateRegistry();
        assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);
    }
}
