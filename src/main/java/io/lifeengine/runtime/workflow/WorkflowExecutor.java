package io.lifeengine.runtime.workflow;

import java.util.UUID;
import org.springframework.security.core.Authentication;

/** Starts and cancels asynchronous workflow execution for a run. */
public interface WorkflowExecutor {

    /**
     * Schedule a workflow run.
     *
     * @param caller the inbound {@link Authentication} captured from the controller's Reactor
     *     Context (Phase-1 JWT pass-through). May be {@code null} when security is disabled or
     *     the caller is anonymous; in that case no SecurityContext is attached to the workflow
     *     chain. When non-null, implementations must attach it to the workflow's Reactor
     *     Context via {@code ReactiveSecurityContextHolder.withAuthentication(caller)} so that
     *     downstream WebClient filters (e.g. {@code CryptobotWebClientConfiguration#jwtPropagationFilter})
     *     can read the bearer token via {@code ReactiveSecurityContextHolder.getContext()}.
     */
    void schedule(
            UUID runId,
            WorkflowDefinition definition,
            String input,
            String correlationId,
            Authentication caller);

    boolean requestCancel(UUID runId);
}
