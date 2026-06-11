package io.lifeengine.runtime.ext.businesschat.channels;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChannelTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {"WEB_CHAT", "EMAIL", "WHATSAPP", "INSTAGRAM"})
    void parse_acceptsSupportedWireNames(String wireName) {
        Assertions.assertThat(ChannelType.parse(wireName).wireName()).isEqualTo(wireName);
    }

    @Test
    void parse_normalizesSeparatorsAndCase() {
        Assertions.assertThat(ChannelType.parse("web-chat").wireName()).isEqualTo("WEB_CHAT");
        Assertions.assertThat(ChannelType.parse("whatsapp").wireName()).isEqualTo("WHATSAPP");
    }

    @Test
    void parse_rejectsUnknownChannel() {
        Assertions.assertThatThrownBy(() -> ChannelType.parse("SMS"))
                .isInstanceOf(InvalidChannelTypeException.class);
    }
}
