package io.lifeengine.runtime.api;

import io.lifeengine.runtime.events.GlobalEventFilter;
import io.lifeengine.runtime.events.GlobalEventStreamService;
import io.lifeengine.runtime.observability.RuntimeMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Global Runtime Event Spine endpoint.
 *
 * <p>Exposes {@code GET /api/runtime/events/stream} — a single SSE stream that fans out every
 * runtime event from every run, optionally filtered by workflow id, workflow prefix, or run
 * id. Built on the {@code RunEventPublisher} global multicast sink; no separate event-publish
 * path is introduced.
 *
 * <p>Authentication mirrors the per-run stream: standard {@code Authorization: Bearer …}
 * header, with {@code ?access_token=…} fallback for browser EventSource — that branch is
 * handled in {@code RuntimeJwtAuthenticationWebFilter} which has been extended to recognise
 * this endpoint as an SSE GET.
 *
 * <p>Path-based authorization: {@code /api/runtime/**} already requires {@code RUNTIME_VIEWER}
 * (see {@code RuntimeSecurityConfig}); no extra rule is needed here.
 */
@RestController
@RequestMapping("/api/runtime/events")
public class GlobalRuntimeEventsController {

    private final GlobalEventStreamService streamService;
    private final RuntimeMetrics metrics;

    public GlobalRuntimeEventsController(
            GlobalEventStreamService streamService, RuntimeMetrics metrics) {
        this.streamService = streamService;
        this.metrics = metrics;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<RuntimeEventResponse>> streamGlobal(
            @RequestParam(value = "workflowId", required = false) String workflowId,
            @RequestParam(value = "workflowPrefix", required = false) String workflowPrefix,
            @RequestParam(value = "runId", required = false) String runId) {
        // Reuse the per-run SSE counter — both endpoints represent "an SSE stream is open"
        // from the runtime's POV; splitting metrics is a Phase-2 detail, not a Phase-1 need.
        metrics.recordSseStreamOpened();
        GlobalEventFilter filter = GlobalEventFilter.from(workflowId, workflowPrefix, runId);
        return streamService.stream(filter);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ApiError> badQuery(IllegalArgumentException ex) {
        return Mono.just(new ApiError("invalid_query", ex.getMessage()));
    }

    public record ApiError(String code, String message) {}
}
