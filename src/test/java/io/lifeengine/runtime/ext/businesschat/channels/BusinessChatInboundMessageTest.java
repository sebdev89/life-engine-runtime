package io.lifeengine.runtime.ext.businesschat.channels;

import io.lifeengine.runtime.ext.businesschat.BusinessChatReplyIo;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class BusinessChatInboundMessageTest {

    @Test
    void toWorkflowInput_usesChannelWireName() {
        BusinessChatInboundMessage inbound =
                new BusinessChatInboundMessage(
                        ChannelType.WHATSAPP,
                        "barberia-demo",
                        "conv-1",
                        "Cliente Demo",
                        "wa-123",
                        "Hola");

        BusinessChatReplyIo.Input input = inbound.toWorkflowInput();

        Assertions.assertThat(input.channel()).isEqualTo("WHATSAPP");
        Assertions.assertThat(input.botId()).isEqualTo("barberia-demo");
        Assertions.assertThat(input.customer().externalId()).isEqualTo("wa-123");
    }

    @Test
    void fromWorkflowInput_roundTrips() {
        BusinessChatReplyIo.Input input =
                new BusinessChatReplyIo.Input(
                        "EMAIL",
                        "inmobiliaria-demo",
                        "conv-mail-1",
                        new BusinessChatReplyIo.Customer("Ana", "mail-ana"),
                        "Consulta",
                        null);

        BusinessChatInboundMessage inbound = BusinessChatInboundMessage.fromWorkflowInput(input);

        Assertions.assertThat(inbound.channel()).isEqualTo(ChannelType.EMAIL);
        Assertions.assertThat(inbound.toWorkflowInput()).isEqualTo(input);
    }
}
