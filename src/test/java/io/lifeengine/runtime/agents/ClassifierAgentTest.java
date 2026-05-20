package io.lifeengine.runtime.agents;

import org.junit.jupiter.api.Test;

class ClassifierAgentTest {

    @Test
    void parseClassification_extractsLabel() {
        org.assertj.core.api.Assertions.assertThat(ClassifierAgent.parseClassification("INFO"))
                .isEqualTo("INFO");
        org.assertj.core.api.Assertions.assertThat(ClassifierAgent.parseClassification("The label is RISK"))
                .isEqualTo("RISK");
        org.assertj.core.api.Assertions.assertThat(ClassifierAgent.parseClassification("unknown stuff"))
                .isEqualTo("UNKNOWN");
    }
}
