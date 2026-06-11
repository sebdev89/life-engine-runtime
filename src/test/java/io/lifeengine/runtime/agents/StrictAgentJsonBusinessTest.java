package io.lifeengine.runtime.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.runtime.ext.businesschat.BusinessChatIntents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StrictAgentJsonBusinessTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "greeting",
                "pricing",
                "booking",
                "location",
                "schedule",
                "support",
                "complaint",
                "human_handoff"
            })
    void parseBusinessContext_acceptsAllIntents(String intent) {
        String raw =
                """
                {
                  "intent": "%s",
                  "confidence": "HIGH",
                  "handoffRequired": false,
                  "leadCaptured": false,
                  "contextNotes": "note"
                }
                """
                        .formatted(intent);

        assertThat(StrictAgentJson.parseBusinessContext(raw).intent()).isEqualTo(intent);
    }

    @Test
    void parseBusinessContext_normalizesIntentCaseAndSeparators() {
        var out =
                StrictAgentJson.parseBusinessContext(
                        """
                        {
                          "intent": "Human-Handoff",
                          "confidence": "medium",
                          "handoffRequired": true,
                          "leadCaptured": false,
                          "contextNotes": "Escalation requested."
                        }
                        """);

        assertThat(out.intent()).isEqualTo("human_handoff");
        assertThat(out.confidence()).isEqualTo("MEDIUM");
        assertThat(out.handoffRequired()).isTrue();
    }

    @Test
    void parseBusinessContext_rejectsLegacyBusinessInfoIntent() {
        assertThatThrownBy(
                        () ->
                                StrictAgentJson.parseBusinessContext(
                                        """
                                        {
                                          "intent": "business_info",
                                          "confidence": "HIGH",
                                          "handoffRequired": false,
                                          "leadCaptured": false,
                                          "contextNotes": "note"
                                        }
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intent must be one of");
    }

    @Test
    void parseBusinessReply_acceptsPricingIntent() {
        var out =
                StrictAgentJson.parseBusinessReply(
                        """
                        {
                          "response": "El combo sale $12000.",
                          "intent": "pricing",
                          "confidence": "HIGH",
                          "handoffRequired": false,
                          "leadCaptured": false,
                          "channel": "WEB_CHAT"
                        }
                        """);

        assertThat(out.intent()).isEqualTo("pricing");
        assertThat(out.response()).contains("12000");
        assertThat(out.sources()).isEmpty();
    }

    @Test
    void parseBusinessReply_acceptsOptionalSources() {
        var out =
                StrictAgentJson.parseBusinessReply(
                        """
                        {
                          "response": "La garantía es de 6 meses.",
                          "intent": "support",
                          "confidence": "HIGH",
                          "handoffRequired": false,
                          "leadCaptured": false,
                          "channel": "WEB_CHAT",
                          "sources": [
                            {"title": "Garantía", "chunkId": "abc-123", "score": 0.87}
                          ]
                        }
                        """);

        assertThat(out.sources()).hasSize(1);
        assertThat(out.sources().get(0).title()).isEqualTo("Garantía");
        assertThat(out.sources().get(0).chunkId()).isEqualTo("abc-123");
        assertThat(out.sources().get(0).score()).isEqualTo(0.87);
    }

    @Test
    void promptIntentsMatchValidationSet() {
        assertThat(BusinessChatIntents.ALL)
                .containsExactly(
                        "greeting",
                        "pricing",
                        "booking",
                        "location",
                        "schedule",
                        "support",
                        "complaint",
                        "human_handoff");
    }
}
