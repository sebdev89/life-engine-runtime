package io.lifeengine.runtime.ext.businesschat;

import jakarta.annotation.PostConstruct;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of {@link BusinessBotDefinition}s keyed by {@code botId}.
 *
 * <p>Designed to be populated at bootstrap time and consulted by business-chat agents. Database
 * backing can replace the in-memory store later without changing agent code.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BusinessBotRegistry {

    private final ConcurrentMap<String, BusinessBotDefinition> bots = new ConcurrentHashMap<>();

    @PostConstruct
    void registerDefaults() {
        register(barberiaDemo());
        register(inmobiliariaDemo());
        register(consultorioDemo());
    }

    public void register(BusinessBotDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definition.botId() == null || definition.botId().isBlank()) {
            throw new IllegalArgumentException("missing or empty field: botId");
        }
        bots.put(definition.botId().trim(), definition);
    }

    public BusinessBotDefinition require(String botId) {
        if (botId == null || botId.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: botId");
        }
        BusinessBotDefinition definition = bots.get(botId.trim());
        if (definition == null) {
            throw new BusinessBotNotFoundException(botId.trim());
        }
        return definition;
    }

    public Optional<BusinessBotDefinition> find(String botId) {
        if (botId == null || botId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(bots.get(botId.trim()));
    }

    public Collection<BusinessBotDefinition> all() {
        return List.copyOf(bots.values());
    }

    static BusinessBotDefinition barberiaDemo() {
        return new BusinessBotDefinition(
                "barberia-demo",
                "Barbería Demo",
                "Cercano, profesional, estilo WhatsApp.",
                List.of(
                        "No inventar precios.",
                        "No confirmar turnos reales.",
                        "Si el cliente quiere reservar, pedir nombre y horario preferido.",
                        "Si no sabe, ofrecer derivar a humano.",
                        "Responder breve, claro y natural."),
                List.of(
                        new BusinessBotDefinition.Faq("¿Cuánto sale un corte?", "Corte: $8000"),
                        new BusinessBotDefinition.Faq("¿Cuánto sale la barba?", "Barba: $5000"),
                        new BusinessBotDefinition.Faq("¿Cuánto sale corte + barba?", "Corte + barba: $12000"),
                        new BusinessBotDefinition.Faq(
                                "¿Cuáles son los horarios?",
                                "Lunes a viernes de 10 a 20. Sábados de 10 a 16."),
                        new BusinessBotDefinition.Faq("¿Dónde están ubicados?", "Palermo, CABA")),
                List.of(
                        new BusinessBotDefinition.SuggestedPrompt(
                                "Precios", "¿Cuánto sale corte y barba?"),
                        new BusinessBotDefinition.SuggestedPrompt(
                                "Horarios", "¿Cuáles son los horarios?"),
                        new BusinessBotDefinition.SuggestedPrompt("Ubicación", "¿Dónde están?"),
                        new BusinessBotDefinition.SuggestedPrompt(
                                "Reservar turno", "Quiero reservar un turno")));
    }

    static BusinessBotDefinition inmobiliariaDemo() {
        return new BusinessBotDefinition(
                "inmobiliaria-demo",
                "Inmobiliaria Demo",
                "Profesional, claro y confiable, estilo WhatsApp.",
                List.of(
                        "No inventar precios ni disponibilidad de propiedades.",
                        "No confirmar visitas reales ni reservas.",
                        "Si el cliente quiere agendar una visita, pedir nombre, teléfono y horario preferido.",
                        "Si no sabe, ofrecer derivar a un asesor humano.",
                        "Responder breve, claro y natural."),
                List.of(
                        new BusinessBotDefinition.Faq(
                                "¿Tienen departamentos en alquiler?",
                                "Sí, tenemos opciones de 1 y 2 ambientes en Palermo y Belgrano desde USD 650/mes."),
                        new BusinessBotDefinition.Faq(
                                "¿Cuánto sale un 2 ambientes en venta?",
                                "Departamentos de 2 ambientes desde USD 120.000 según zona y edificio."),
                        new BusinessBotDefinition.Faq(
                                "¿Cuáles son los horarios de atención?",
                                "Lunes a viernes de 9 a 19. Sábados de 10 a 14."),
                        new BusinessBotDefinition.Faq(
                                "¿Dónde están ubicados?",
                                "Av. Santa Fe 3200, Palermo, CABA."),
                        new BusinessBotDefinition.Faq(
                                "¿Cómo agendo una visita?",
                                "Indicá nombre, teléfono y horario preferido; un asesor te confirma la visita.")),
                List.of(
                        new BusinessBotDefinition.SuggestedPrompt(
                                "Precios", "¿Cuánto sale un 2 ambientes en venta?"),
                        new BusinessBotDefinition.SuggestedPrompt(
                                "Horarios", "¿Cuáles son los horarios de atención?"),
                        new BusinessBotDefinition.SuggestedPrompt(
                                "Ubicación", "¿Dónde están ubicados?"),
                        new BusinessBotDefinition.SuggestedPrompt(
                                "Agendar visita", "Quiero agendar una visita")));
    }

    static BusinessBotDefinition consultorioDemo() {
        return new BusinessBotDefinition(
                "consultorio-demo",
                "Consultorio Médico Demo",
                "Empático, claro y profesional, estilo WhatsApp.",
                List.of(
                        "No dar diagnósticos ni indicar tratamientos.",
                        "No confirmar turnos reales.",
                        "Si el cliente quiere un turno, pedir nombre, DNI y horario preferido.",
                        "Ante urgencias, recomendar acudir a guardia o llamar al 107.",
                        "Si no sabe, ofrecer derivar a recepción humana.",
                        "Responder breve, claro y natural."),
                List.of(
                        new BusinessBotDefinition.Faq(
                                "¿Qué especialidades atienden?",
                                "Clínica médica, pediatría y cardiología."),
                        new BusinessBotDefinition.Faq(
                                "¿Cuánto sale una consulta de clínica?",
                                "Consulta de clínica: $25.000 (particular)."),
                        new BusinessBotDefinition.Faq(
                                "¿Cuáles son los horarios?",
                                "Lunes a viernes de 8 a 20. Sábados de 9 a 13."),
                        new BusinessBotDefinition.Faq(
                                "¿Dónde están ubicados?",
                                "Av. Córdoba 4500, Almagro, CABA."),
                        new BusinessBotDefinition.Faq(
                                "¿Trabajan con obras sociales?",
                                "Sí, consultá por tu cobertura al solicitar turno.")),
                List.of(
                        new BusinessBotDefinition.SuggestedPrompt(
                                "Precios", "¿Cuánto sale una consulta de clínica?"),
                        new BusinessBotDefinition.SuggestedPrompt(
                                "Horarios", "¿Cuáles son los horarios?"),
                        new BusinessBotDefinition.SuggestedPrompt(
                                "Ubicación", "¿Dónde están ubicados?"),
                        new BusinessBotDefinition.SuggestedPrompt(
                                "Reservar turno", "Quiero reservar un turno")));
    }
}
