package io.lifeengine.runtime.domain;

/** Canonical run lifecycle for the generic runtime. */
public enum RunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
