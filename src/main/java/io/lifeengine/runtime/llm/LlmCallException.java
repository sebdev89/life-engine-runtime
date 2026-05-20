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

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }
}
