package io.lifeengine.runtime.ext.cryptomarketreview.cryptobot;

/**
 * Typed failure when calling {@code cryptobot-service}. Mirrors the shape of
 * {@code io.lifeengine.runtime.llm.LlmCallException} so tools can produce rich
 * {@code TOOL_FAILED} event payloads (status code, endpoint, body preview).
 */
public class CryptobotCallException extends RuntimeException {

    private final Integer statusCode;
    private final String endpoint;
    private final String responseBody;

    public CryptobotCallException(String message, Integer statusCode, String endpoint, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.endpoint = endpoint;
        this.responseBody = responseBody;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public String endpoint() {
        return endpoint;
    }

    public String responseBody() {
        return responseBody;
    }
}
