package io.lifeengine.runtime.ext.businesschat;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BusinessConversationContextTest {

    private BusinessConversationContext context;

    @BeforeEach
    void setUp() {
        context = new BusinessConversationContext();
    }

    @Test
    void history_isEmptyForUnknownConversation() {
        Assertions.assertThat(context.history("conv-new")).isEmpty();
    }

    @Test
    void append_andHistory_roundTrip() {
        context.append("conv-1", "Hola", "Hola, ¿en qué te ayudo?");

        Assertions.assertThat(context.history("conv-1"))
                .containsExactly(new BusinessConversationContext.Interaction("Hola", "Hola, ¿en qué te ayudo?"));
    }

    @Test
    void append_keepsOnlyLastTenInteractions() {
        for (int i = 1; i <= 12; i++) {
            context.append("conv-1", "msg-" + i, "reply-" + i);
        }

        Assertions.assertThat(context.history("conv-1"))
                .hasSize(BusinessConversationContext.MAX_INTERACTIONS)
                .extracting(BusinessConversationContext.Interaction::customerMessage)
                .containsExactly(
                        "msg-3",
                        "msg-4",
                        "msg-5",
                        "msg-6",
                        "msg-7",
                        "msg-8",
                        "msg-9",
                        "msg-10",
                        "msg-11",
                        "msg-12");
    }

    @Test
    void recentHistory_isEmptyForUnknownConversation() {
        Assertions.assertThat(context.recentHistory("conv-new")).isEmpty();
    }

    @Test
    void recentHistory_returnsFullHistory_whenBelowPromptCap() {
        context.append("conv-1", "msg-1", "reply-1");
        context.append("conv-1", "msg-2", "reply-2");

        Assertions.assertThat(context.recentHistory("conv-1"))
                .extracting(BusinessConversationContext.Interaction::customerMessage)
                .containsExactly("msg-1", "msg-2");
    }

    @Test
    void recentHistory_keepsOnlyMostRecentTurns_whenConversationOutgrowsPromptCap() {
        // MAX_INTERACTIONS keeps 10 turns in memory, but the prompt should only ever
        // see the last MAX_PROMPT_INTERACTIONS of them regardless of how long the
        // conversation runs (KAN-156 — prompt size must not scale with turn count).
        for (int i = 1; i <= 9; i++) {
            context.append("conv-1", "msg-" + i, "reply-" + i);
        }

        Assertions.assertThat(context.recentHistory("conv-1"))
                .hasSize(BusinessConversationContext.MAX_PROMPT_INTERACTIONS)
                .extracting(BusinessConversationContext.Interaction::customerMessage)
                .containsExactly("msg-6", "msg-7", "msg-8", "msg-9");

        Assertions.assertThat(context.history("conv-1")).hasSize(9);
    }

    @Test
    void conversationsAreIsolatedByConversationId() {
        context.append("conv-a", "Hola A", "Reply A");
        context.append("conv-b", "Hola B", "Reply B");

        Assertions.assertThat(context.history("conv-a"))
                .containsExactly(new BusinessConversationContext.Interaction("Hola A", "Reply A"));
        Assertions.assertThat(context.history("conv-b"))
                .containsExactly(new BusinessConversationContext.Interaction("Hola B", "Reply B"));
    }
}
