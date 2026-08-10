package io.lifeengine.runtime.api;

import io.lifeengine.runtime.agents.AgentNotFoundException;
import io.lifeengine.runtime.core.RunNotFoundException;
import io.lifeengine.runtime.core.RunService;
import io.lifeengine.runtime.core.UnknownWorkflowException;
import io.lifeengine.runtime.domain.EventSequence;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
    public Flux<ServerSentEvent<RuntimeEventResponse>> streamRun(
            @PathVariable UUID runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        metrics.recordSseStreamOpened();
        LastEventId parsed = parseLastEventId(lastEventId);
        metrics.recordStreamResume(parsed.outcome());
        return eventStreamService.stream(runId, parsed.seq());
    }

    /** @deprecated Prefer {@code /stream}; kept for cockpit compatibility. */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<RuntimeEventResponse>> streamEventsLegacy(
            @PathVariable UUID runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        return streamRun(runId, lastEventId);
    }

    /**
     * El navegador reenvía el último id recibido al reconectar. Desde ADR-RT-012 ese id es el
     * {@code seq}.
     *
     * <p>Un id no numérico es {@code 400 validation_failed}, tal como SPEC-004 §2ter congela la
     * semántica de error. Eso incluye el UUID que mandaría un cliente anterior a F1: la spec no
     * define compatibilidad para ese caso, y tragárselo devolviendo el run entero sería cambiar en
     * el código una semántica que la revisión de arquitectura aprobó de otra forma.
     */
    private LastEventId parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return new LastEventId(EventSequence.UNASSIGNED, "ok");
        }
        try {
            long parsed = Long.parseLong(lastEventId.trim());
            if (parsed < 0) {
                throw new InvalidLastEventIdException(lastEventId);
            }
            return new LastEventId(EventSequence.of(parsed), "ok");
        } catch (NumberFormatException notASeq) {
            metrics.recordStreamResume("invalid");
            throw new InvalidLastEventIdException(lastEventId);
        }
    }

    private record LastEventId(EventSequence seq, String outcome) {}

    /** {@code Last-Event-ID} que no es un {@code seq}. SPEC-004 §2ter: 400, no reintentable. */
    static final class InvalidLastEventIdException extends RuntimeException {
        InvalidLastEventIdException(String received) {
            super("Last-Event-ID must be a numeric event sequence, got: " + received);
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(InvalidLastEventIdException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ApiError> invalidLastEventId(InvalidLastEventIdException ex) {
        return Mono.just(new ApiError("validation_failed", ex.getMessage()));
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
