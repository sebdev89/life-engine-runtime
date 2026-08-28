package io.lifeengine.runtime.core;

import io.lifeengine.runtime.domain.Run;

/**
 * Result of a start-run request. {@code created} is {@code false} when an idempotency key matched
 * an existing run and no new run was created — the REST layer maps that to HTTP 200 instead of 201.
 */
public record StartRunOutcome(Run run, boolean created) {}
