package io.lifeengine.runtime.ext.businesschat;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class BusinessChatReplyPromptsTest {

    @Test
    void replyPrompt_includesConversationAndTonePolicies() {
        String systemMessage = BusinessChatReplyPrompts.reply().systemMessage();

        Assertions.assertThat(systemMessage).contains("greetingPolicy");
        Assertions.assertThat(systemMessage).contains("answerFirstPolicy");
        Assertions.assertThat(systemMessage).contains("humanTonePolicy");
        Assertions.assertThat(systemMessage).contains("channelPolicy");
        Assertions.assertThat(systemMessage).contains("handoffPolicy");
        Assertions.assertThat(systemMessage).contains("conversationHistory");
        Assertions.assertThat(systemMessage).contains("botProfile");
        Assertions.assertThat(systemMessage).contains("como asistente virtual");
    }

    @Test
    void contextPrompt_mentionsBotProfile() {
        String systemMessage = BusinessChatReplyPrompts.context().systemMessage();

        Assertions.assertThat(systemMessage).contains("botProfile");
        Assertions.assertThat(systemMessage).contains("conversationHistory");
    }
}
