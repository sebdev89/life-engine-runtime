package io.lifeengine.runtime.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.runtime.llm.LlmModelRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmModelRoleMetricsTest {

    private SimpleMeterRegistry registry;
    private RuntimeMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RuntimeMetrics(registry);
    }

    @Test
    void recordLlmCall_withRole_producesModelRoleTag() {
        metrics.recordLlmCall("gemma3:4b", "OK", LlmModelRole.CHAT);

        Counter counter = registry.find("runtime.llm.calls").tag("model_role", "chat").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordLlmCall_withNullRole_producesDefaultTag() {
        metrics.recordLlmCall("Qwen/Qwen2.5-Coder-3B-Instruct", "OK", (LlmModelRole) null);

        Counter counter = registry.find("runtime.llm.calls").tag("model_role", "default").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordLlmCall_allRoles_produceCorrectTags() {
        metrics.recordLlmCall("fast-model", "OK", LlmModelRole.FAST);
        metrics.recordLlmCall("smart-model", "OK", LlmModelRole.SMART);
        metrics.recordLlmCall("coding-model", "OK", LlmModelRole.CODING);

        assertThat(registry.find("runtime.llm.calls").tag("model_role", "fast").counter()).isNotNull();
        assertThat(registry.find("runtime.llm.calls").tag("model_role", "smart").counter()).isNotNull();
        assertThat(registry.find("runtime.llm.calls").tag("model_role", "coding").counter()).isNotNull();
    }

    @Test
    void recordLlmFailure_withRole_producesModelRoleTag() {
        metrics.recordLlmFailure("gemma3:4b", LlmModelRole.CHAT);

        Counter counter = registry.find("runtime.llm.failures").tag("model_role", "chat").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordLlmFailure_withNullRole_producesDefaultTag() {
        metrics.recordLlmFailure("Qwen/Qwen2.5-Coder-3B-Instruct", (LlmModelRole) null);

        Counter counter =
                registry.find("runtime.llm.failures").tag("model_role", "default").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordLlmCall_legacyOverloadWithoutRole_stillWorks() {
        metrics.recordLlmCall("some-model", "OK");

        Counter counter = registry.find("runtime.llm.calls").tag("model", "some-model").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordLlmFailure_legacyOverloadWithoutRole_stillWorks() {
        metrics.recordLlmFailure("some-model");

        Counter counter = registry.find("runtime.llm.failures").tag("model", "some-model").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
