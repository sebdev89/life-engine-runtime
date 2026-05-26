package io.lifeengine.runtime.llm;

/** Raised when a vLLM / OpenAI-compatible chat completion call fails (no silent fallback). */
public class LlmCallException extends RuntimeException {

    private final Integer statusCode;
    private final String responseBody;
    private final String endpoint;
    private final String model;
    private final String requestSummary;

    public LlmCallException(
            String message,
            Integer statusCode,
            String responseBody,
            String endpoint,
            String model,
            String requestSummary,
            Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
        this.endpoint = endpoint;
        this.model = model;
        this.requestSummary = requestSummary;
    }

    public static LlmCallException httpFailure(
            int statusCode,
            String responseBody,
            String endpoint,
            String model,
            String requestSummary,
            Throwable cause) {
        String body = responseBody == null ? "" : responseBody;
        String message =
                "LLM HTTP "
                        + statusCode
                        + " at "
                        + endpoint
                        + (body.isBlank() ? "" : ": " + truncate(body, 300));
        return new LlmCallException(message, statusCode, body, endpoint, model, requestSummary, cause);
    }

    public static LlmCallException transport(
            String endpoint, String model, String requestSummary, Throwable cause) {
        String message =
                cause.getMessage() == null
                        ? "LLM transport error at " + endpoint
                        : cause.getMessage();
        return new LlmCallException(message, null, "", endpoint, model, requestSummary, cause);
    }

    public Integer statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }

    public String endpoint() {
        return endpoint;
    }

    public String model() {
        return model;
    }

    public String requestSummary() {
        return requestSummary;
    }

    /**
     * Classifies the failure as transient (safe to retry) or permanent (must surface).
     *
     * <p>Transient = HTTP 502/503/504, request timeout, or connection reset / connect failure.
     * Everything else — HTTP 4xx (including 400/401/403/404), validation/parse errors, agent
     * failures — is treated as permanent and never retried.
     */
    public boolean isTransient() {
        if (statusCode != null) {
            int code = statusCode;
            return code == 502 || code == 503 || code == 504;
        }
        Throwable root = rootCause(getCause());
        if (root == null) {
            return false;
        }
        if (root instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        if (root instanceof java.net.ConnectException) {
            return true;
        }
        String className = root.getClass().getName();
        if (className.endsWith("PrematureCloseException")) {
            return true;
        }
        if (root instanceof java.io.IOException) {
            String message = root.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                return lower.contains("connection reset")
                        || lower.contains("connection closed")
                        || lower.contains("timed out")
                        || lower.contains("timeout");
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }
}
