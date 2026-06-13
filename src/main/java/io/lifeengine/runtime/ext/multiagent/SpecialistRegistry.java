package io.lifeengine.runtime.ext.multiagent;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** In-memory specialist catalog for multi-agent delegation (Phase 5 scaffold). */
@Component
public class SpecialistRegistry {

    private final List<SpecialistDefinition> specialists =
            List.of(
                    new SpecialistDefinition(
                            "legal-specialist",
                            "legal",
                            "business-chat.reply.v1",
                            List.of("handoff", "labor", "legal")),
                    new SpecialistDefinition(
                            "ecommerce-specialist",
                            "ecommerce",
                            "business-chat.reply.v1",
                            List.of("product", "order", "complaint")),
                    new SpecialistDefinition(
                            "general-specialist",
                            "*",
                            "business-chat.reply.v1",
                            List.of("*")));

    public SpecialistDefinition resolve(String tenantId, String intent) {
        String normalizedTenant = blankTo(tenantId, "*");
        String normalizedIntent = blankTo(intent, "*");

        Optional<SpecialistDefinition> tenantMatch =
                specialists.stream()
                        .filter(s -> s.tenantId().equalsIgnoreCase(normalizedTenant))
                        .filter(s -> s.matchesIntent(normalizedIntent))
                        .findFirst();
        if (tenantMatch.isPresent()) {
            return tenantMatch.get();
        }
        return specialists.stream()
                .filter(s -> "*".equals(s.tenantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no specialist fallback"));
    }

    public List<SpecialistDefinition> list() {
        return specialists;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase();
    }

    public record SpecialistDefinition(
            String specialistId, String tenantId, String workflowId, List<String> intents) {

        public SpecialistDefinition {
            intents = intents == null ? List.of() : List.copyOf(intents);
        }

        boolean matchesIntent(String intent) {
            return intents.contains("*") || intents.stream().anyMatch(i -> i.equalsIgnoreCase(intent));
        }
    }
}
