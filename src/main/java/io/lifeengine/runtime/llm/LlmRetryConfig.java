package io.lifeengine.runtime.llm;

/**
 * Conservative retry policy for transient LLM transport/provider failures.
 *
 * <p>{@code maxAttempts} counts retries (NOT total attempts): {@code 0} disables retry, {@code 2}
 * allows up to 2 additional attempts after the initial call (3 total). Only transient failures
 * classified by {@link LlmCallException#isTransient()} are retried — never tool calls, never
 * agent stages, never validation/parse failures.
 */
public record LlmRetryConfig(boolean enabled, int maxAttempts, long backoffMillis) {

    public static final LlmRetryConfig DISABLED = new LlmRetryConfig(false, 0, 0L);

    public LlmRetryConfig {
        if (maxAttempts < 0) {
            maxAttempts = 0;
        }
        if (backoffMillis < 0) {
            backoffMillis = 0L;
        }
    }

    /** Returns true when retry is enabled and at least one retry is allowed. */
    public boolean active() {
        return enabled && maxAttempts > 0;
    }
}
