package io.lifeengine.runtime.extension;

/**
 * Extension point for future vertical modules (e.g. {@code example.vertical.workflow}).
 * Implement in the vertical JAR; call {@link #register(RuntimeRegistry)} at bootstrap.
 */
public interface RuntimeModule {

    String moduleId();

    void register(RuntimeRegistry registry);
}
