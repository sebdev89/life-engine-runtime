package io.lifeengine.runtime.ext.businesschat;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The in-memory conversation store was disabled as part of {@code H2} in
 * {@code StabilizationAudit.md}. business-chat-service / Postgres is the
 * authoritative transcript; Runtime is fully stateless. These tests pin
 * the no-op semantics so future work cannot silently reintroduce the
 * split-memory hazard.
 */
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
    void history_isEmptyForNullOrBlankConversationId() {
        Assertions.assertThat(context.history(null)).isEmpty();
        Assertions.assertThat(context.history("")).isEmpty();
        Assertions.assertThat(context.history("   ")).isEmpty();
    }

    @Test
    void append_doesNotPopulateHistory_h2NoOp() {
        // H2 — appends must be silently ignored; the Postgres transcript on
        // business-chat-service is the only source of truth.
        context.append("conv-1", "Hola", "Hola, ¿en qué te ayudo?");

        Assertions.assertThat(context.history("conv-1")).isEmpty();
    }

    @Test
    void append_doesNotThrowOnInvalidArgs_h2NoOp() {
        // The legacy implementation validated arguments to surface caller
        // bugs at boundaries. Now that the operation is a no-op there is
        // nothing to validate; silently ignoring garbage is preferable to
        // throwing from a method that does nothing useful regardless.
        Assertions.assertThatCode(() -> context.append(null, null, null))
                .doesNotThrowAnyException();
        Assertions.assertThatCode(() -> context.append("", "", ""))
                .doesNotThrowAnyException();
    }

    @Test
    void conversationsRemainEmptyAfterAppendsAcrossManyConversations() {
        for (int i = 0; i < 25; i++) {
            context.append("conv-" + i, "msg-" + i, "reply-" + i);
        }
        for (int i = 0; i < 25; i++) {
            Assertions.assertThat(context.history("conv-" + i)).isEmpty();
        }
    }
}
