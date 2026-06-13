package io.lifeengine.runtime.ext.supervisor;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** In-memory routing catalog — tenant/channel/intent → target workflow. */
@Component
public class SupervisorRouteCatalog {

    private final List<RouteRule> rules =
            List.of(
                    new RouteRule("legal", "*", "handoff", "business-chat.reply.v1", "legal-handoff"),
                    new RouteRule("legal", "*", "*", "business-chat.reply.v1", "legal-default"),
                    new RouteRule("ecommerce", "whatsapp", "*", "business-chat.reply.v1", "ecommerce-whatsapp"),
                    new RouteRule("ecommerce", "*", "*", "business-chat.reply.v1", "ecommerce-default"),
                    new RouteRule("*", "*", "*", "business-chat.reply.v1", "global-fallback"));

    public RouteDecision resolve(String tenantId, String channel, String intent) {
        String normalizedTenant = blankTo(tenantId, "*");
        String normalizedChannel = blankTo(channel, "*");
        String normalizedIntent = blankTo(intent, "*");

        for (RouteRule rule : rules) {
            if (rule.matches(normalizedTenant, normalizedChannel, normalizedIntent)) {
                return new RouteDecision(rule.workflowId(), rule.routeReason(), "supervisor-v1");
            }
        }
        return new RouteDecision("business-chat.reply.v1", "hard-fallback", "supervisor-v1");
    }

    public List<RouteRule> rules() {
        return rules;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase();
    }

    public record RouteRule(
            String tenantId, String channel, String intent, String workflowId, String routeReason) {

        boolean matches(String tenantId, String channel, String intent) {
            return wildcard(tenantId, this.tenantId)
                    && wildcard(channel, this.channel)
                    && wildcard(intent, this.intent);
        }

        private static boolean wildcard(String actual, String pattern) {
            return "*".equals(pattern) || pattern.equalsIgnoreCase(Optional.ofNullable(actual).orElse(""));
        }
    }

    public record RouteDecision(String workflowId, String routeReason, String policyVersion) {}
}
