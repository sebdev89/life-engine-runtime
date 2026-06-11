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
    void conversationsAreIsolatedByConversationId() {
        context.append("conv-a", "Hola A", "Reply A");
        context.append("conv-b", "Hola B", "Reply B");

        Assertions.assertThat(context.history("conv-a"))
                .containsExactly(new BusinessConversationContext.Interaction("Hola A", "Reply A"));
        Assertions.assertThat(context.history("conv-b"))
                .containsExactly(new BusinessConversationContext.Interaction("Hola B", "Reply B"));
    }
}
