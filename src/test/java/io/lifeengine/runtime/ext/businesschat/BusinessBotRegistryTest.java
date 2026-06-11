package io.lifeengine.runtime.ext.businesschat;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BusinessBotRegistryTest {

    private BusinessBotRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new BusinessBotRegistry();
        registry.registerDefaults();
    }

    @Test
    void registerDefaults_loadsAllDemoBots() {
        Assertions.assertThat(registry.all()).hasSize(3);
        Assertions.assertThat(registry.find("barberia-demo")).isPresent();
        Assertions.assertThat(registry.find("inmobiliaria-demo")).isPresent();
        Assertions.assertThat(registry.find("consultorio-demo")).isPresent();
    }

    @Test
    void require_selectsBotById() {
        BusinessBotDefinition barberia = registry.require("barberia-demo");
        BusinessBotDefinition inmobiliaria = registry.require("inmobiliaria-demo");
        BusinessBotDefinition consultorio = registry.require("consultorio-demo");

        Assertions.assertThat(barberia.businessName()).isEqualTo("Barbería Demo");
        Assertions.assertThat(inmobiliaria.businessName()).isEqualTo("Inmobiliaria Demo");
        Assertions.assertThat(consultorio.businessName()).isEqualTo("Consultorio Médico Demo");
        Assertions.assertThat(consultorio.faqs()).anyMatch(faq -> faq.answer().contains("cardiología"));
    }

    @Test
    void require_rejectsUnknownBotId() {
        Assertions.assertThatThrownBy(() -> registry.require("unknown"))
                .isInstanceOf(BusinessBotNotFoundException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void require_rejectsBlankBotId() {
        Assertions.assertThatThrownBy(() -> registry.require("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("botId");
    }

    @Test
    void find_returnsEmptyForUnknownBot() {
        Assertions.assertThat(registry.find("missing")).isEmpty();
    }

    @Test
    void register_overwritesExistingBot() {
        registry.register(
                new BusinessBotDefinition(
                        "barberia-demo",
                        "Override",
                        "Formal",
                        java.util.List.of("Rule"),
                        java.util.List.of(new BusinessBotDefinition.Faq("Q?", "A.")),
                        java.util.List.of(
                                new BusinessBotDefinition.SuggestedPrompt("Test", "Hola"))));

        Assertions.assertThat(registry.require("barberia-demo").businessName()).isEqualTo("Override");
    }
}
