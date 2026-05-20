package io.lifeengine.runtime.api;

import io.lifeengine.runtime.core.RunNotFoundException;
import io.lifeengine.runtime.core.RunService;
import io.lifeengine.runtime.core.UnknownWorkflowException;
import io.lifeengine.runtime.events.RunEventStreamService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

    public RuntimeRestController(RunService runService, RunEventStreamService eventStreamService) {
        this.runService = runService;
        this.eventStreamService = eventStreamService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RunResponse> startRun(@Valid @RequestBody StartRunRequest request) {
        return runService.startRun(request).map(RunResponse::from);
    }

    @GetMapping("/{runId}")
    public Mono<RunResponse> getRun(@PathVariable UUID runId) {
        return runService.getRun(runId).map(RunResponse::from);
    }

    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<RuntimeEventResponse>> streamEvents(@PathVariable UUID runId) {
        return eventStreamService.stream(runId);
    }

    @PostMapping("/{runId}/cancel")
    public Mono<RunResponse> cancelRun(@PathVariable UUID runId) {
        return runService.cancelRun(runId).map(RunResponse::from);
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

    public record ApiError(String code, String message) {}
}
