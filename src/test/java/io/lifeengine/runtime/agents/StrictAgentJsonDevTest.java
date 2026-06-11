package io.lifeengine.runtime.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StrictAgentJsonDevTest {

    @Test
    void parseDevCodeReview_acceptsHappyShape() {
        String raw =
                "{\"findings\":[\"missing null check\"],\"severityHint\":\"HIGH\","
                        + "\"notes\":\"guard clause needed\"}";
        var out = StrictAgentJson.parseDevCodeReview(raw);
        assertThat(out.severityHint()).isEqualTo("HIGH");
        assertThat(out.findings()).hasSize(1);
        assertThat(out.notes()).isEqualTo("guard clause needed");
    }

    @Test
    void parseDevCodeReview_rejectsEmptyFindings() {
        String raw = "{\"findings\":[],\"severityHint\":\"LOW\",\"notes\":\"ok\"}";
        assertThatThrownBy(() -> StrictAgentJson.parseDevCodeReview(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("findings");
    }

    @Test
    void parseDevSummary_acceptsHappyShape() {
        String raw =
                "{\"severity\":\"MEDIUM\",\"summary\":\"needs work\","
                        + "\"recommendations\":[\"add tests\"]}";
        var out = StrictAgentJson.parseDevSummary(raw);
        assertThat(out.severity()).isEqualTo("MEDIUM");
        assertThat(out.recommendations()).containsExactly("add tests");
    }

    @Test
    void parseDevSummary_rejectsInvalidSeverity() {
        String raw = "{\"severity\":\"CRITICAL\",\"summary\":\"x\",\"recommendations\":[\"y\"]}";
        assertThatThrownBy(() -> StrictAgentJson.parseDevSummary(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("severity");
    }

    @Test
    void parseDevKnowledgeAnswer_acceptsHappyShape() {
        String raw =
                "{\"answer\":\"JWT is validated in RuntimeJwtAuthenticationWebFilter.\","
                        + "\"confidence\":\"high\",\"sources\":[{\"title\":\"JWT\",\"chunkId\":\"c-1\",\"score\":0.9}]}";
        var out = StrictAgentJson.parseDevKnowledgeAnswer(raw);
        assertThat(out.confidence()).isEqualTo("high");
        assertThat(out.answer()).contains("RuntimeJwtAuthenticationWebFilter");
        assertThat(out.sources()).hasSize(1);
    }

    @Test
    void parseDevKnowledgeAnswer_rejectsInvalidConfidence() {
        String raw = "{\"answer\":\"x\",\"confidence\":\"certain\",\"sources\":[]}";
        assertThatThrownBy(() -> StrictAgentJson.parseDevKnowledgeAnswer(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }
}
