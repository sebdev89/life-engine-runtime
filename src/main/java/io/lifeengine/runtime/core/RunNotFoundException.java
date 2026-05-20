package io.lifeengine.runtime.core;

import java.util.UUID;

public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(UUID runId) {
        super("Run not found: " + runId);
    }
}
