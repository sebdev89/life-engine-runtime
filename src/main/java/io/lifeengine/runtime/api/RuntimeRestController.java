package io.lifeengine.runtime.api;

import io.lifeengine.runtime.agents.AgentNotFoundException;
import io.lifeengine.runtime.core.RunNotFoundException;
import io.lifeengine.runtime.core.RunService;
import io.lifeengine.runtime.core.UnknownWorkflowException;
import io.lifeengine.runtime.tools.ToolNotFoundException;
import io.lifeengine.runtime.events.RunEventStreamService;
import io.lifeengine.runtime.observability.RuntimeMetrics;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/runtime/runs")
public class RuntimeRestController {

    private final RunService runService;
    private final RunEventStreamService eventStreamService;
    private final RuntimeMetrics metrics;

    public RuntimeRestController(
            RunService runService, RunEventStreamService eventStreamService, RuntimeMetrics metrics) {
        this.runService = runService;
        this.eventStreamService = eventStreamService;
        this.metrics = metrics;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RunResponse> startRun(@Valid @RequestBody StartRunRequest request) {
        return runService.startRun(request).map(RunResponse::from);
    }

    @GetMapping("/{runId}")
    public Mono<RunDetailView> getRun(@PathVariable UUID runId) {
        return runService.getRunDetail(runId).map(RunDetailResponse::toView);
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<RuntimeEventResponse>> streamRun(@PathVariable UUID runId) {
        metrics.recordSseStreamOpened();
        return eventStreamService.stream(runId);
    }

    /** @deprecated Prefer {@code /stream}; kept for cockpit compatibility. */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<RuntimeEventResponse>> streamEventsLegacy(@PathVariable UUID runId) {
        return streamRun(runId);
    }

    @PostMapping("/{runId}/cancel")
    public Mono<RunResponse> cancelRun(@PathVariable UUID runId) {
        return runService.cancelRun(runId).map(RunResponse::from);
    }

    @PostMapping("/{runId}/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> appendRunEvents(
            @PathVariable UUID runId, @Valid @RequestBody AppendRunEventsRequest request) {
        return runService.appendRunEvents(runId, request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(RunNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Mono<ApiError> notFound(RunNotFoundException ex) {
        return Mono.just(new ApiError("not_found", ex.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Mono<ApiError> conflict(IllegalStateException ex) {
        return Mono.just(new ApiError("conflict", ex.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(UnknownWorkflowException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ApiError> unknownWorkflow(UnknownWorkflowException ex) {
        return Mono.just(new ApiError("unknown_workflow", ex.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(AgentNotFoundException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ApiError> unknownAgent(AgentNotFoundException ex) {
        return Mono.just(new ApiError("unknown_agent", ex.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ToolNotFoundException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ApiError> unknownTool(ToolNotFoundException ex) {
        return Mono.just(new ApiError("unknown_tool", ex.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(WebExchangeBindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ApiError> validationFailed(WebExchangeBindException ex) {
        String message =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(err -> err.getField() + ": " + err.getDefaultMessage())
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("Validation failed");
        return Mono.just(new ApiError("validation_failed", message));
    }

    public record ApiError(String code, String message) {}
}
