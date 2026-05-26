package io.lifeengine.runtime.ext.cryptomarketreview.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.cryptomarketreview.cryptobot.CryptobotCallException;
import io.lifeengine.runtime.tools.ToolExecutionResult;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Shared helpers for the seven cryptobot HTTP tools. Centralizes input parsing (each tool needs
 * a symbol from upstream agent output), structured JSON serialization, and the
 * {@code TOOL_STARTED} / {@code TOOL_SUCCEEDED} / {@code TOOL_FAILED} envelope that matches the
 * existing runtime conventions.
 */
public final class CryptoToolSupport {

    private CryptoToolSupport() {}

    /** Normalize a symbol string; uppercase + trim. Returns empty string for {@code null}. */
    public static String normalizeSymbol(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Best-effort symbol extraction from a tool's {@code request.input()} string. Tools call
     * this so they can be invoked with either a raw symbol ({@code "BTCUSDT"}) or a JSON
     * blob containing a {@code symbol} field.
     */
    public static String resolveSymbol(ObjectMapper mapper, String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return "";
        }
        String trimmed = rawInput.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode node = mapper.readTree(trimmed);
                if (node.isObject()) {
                    JsonNode sym = node.get("symbol");
                    if (sym != null && sym.isTextual()) {
                        return normalizeSymbol(sym.asText());
                    }
                }
            } catch (Exception ignore) {
                // fall through and treat the whole input as a symbol literal.
            }
        }
        return normalizeSymbol(trimmed);
    }

    public static void emitToolStarted(WorkflowRunContext ctx, String toolId, String symbol) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("toolId", toolId);
        if (symbol != null && !symbol.isBlank()) {
            attrs.put("symbol", symbol);
        }
        attrs.put("startedAt", Instant.now().toString());
        ctx.emit(EventType.TOOL_STARTED, attrs, false);
    }

    public static void emitToolSucceeded(
            WorkflowRunContext ctx, String toolId, String symbol, long latencyMs, String outputPreview) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("toolId", toolId);
        if (symbol != null && !symbol.isBlank()) {
            attrs.put("symbol", symbol);
        }
        attrs.put("latencyMs", Long.toString(latencyMs));
        attrs.put("outputPreview", WorkflowRunContext.truncate(outputPreview, 240));
        ctx.emit(EventType.TOOL_SUCCEEDED, attrs, false);
    }

    public static Mono<ToolExecutionResult> failTool(
            WorkflowRunContext ctx, String toolId, Throwable ex, long latencyMs) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("toolId", toolId);
        attrs.put("latencyMs", Long.toString(latencyMs));
        attrs.put("error", WorkflowRunContext.truncate(ex.getMessage(), 240));
        if (ex instanceof CryptobotCallException ce) {
            if (ce.statusCode() != null) {
                attrs.put("statusCode", Integer.toString(ce.statusCode()));
            }
            if (ce.endpoint() != null) {
                attrs.put("endpoint", ce.endpoint());
            }
            if (ce.responseBody() != null && !ce.responseBody().isBlank()) {
                attrs.put("responseBodyPreview", WorkflowRunContext.truncate(ce.responseBody(), 240));
            }
        }
        ctx.emit(EventType.TOOL_FAILED, attrs, false);
        return Mono.error(ex);
    }

    public static String toJson(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
