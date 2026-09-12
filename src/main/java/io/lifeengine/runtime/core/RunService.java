package io.lifeengine.runtime.core;

import io.lifeengine.runtime.api.RunDetailResponse;
import io.lifeengine.runtime.api.StartRunRequest;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.events.RunEventPublisher;
import io.lifeengine.runtime.observability.RunLogContext;
import io.lifeengine.runtime.observability.RuntimeMetrics;
import io.lifeengine.runtime.security.RuntimePrincipal;
import io.lifeengine.runtime.workflow.WorkflowRouter;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final RunStore store;
    private final WorkflowRouter workflowRouter;
    private final RunEventPublisher eventPublisher;
    private final RuntimeMetrics metrics;

    public RunService(
            RunStore store,
            WorkflowRouter workflowRouter,
            RunEventPublisher eventPublisher,
            RuntimeMetrics metrics) {
        this.store = store;
        this.workflowRouter = workflowRouter;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
    }

    /**
     * El tenant del llamador, o {@code null} si su token no lo afirma.
     *
     * <p>Sólo los tokens de usuario llevan el claim {@code tenant} (Auth V57). Los de servicio no
     * lo llevan todavía —necesitan {@code act.tenant}, que es W2— así que hoy toda corrida
     * disparada por Business Chat, Dev Agent o ATP queda sin atribuir. Eso es visible en
     * {@code runtime_tenancy_missing_claim_total} y no se disimula con un default.
     */
    private static String tenantOf(Authentication caller) {
        if (caller == null || !(caller.getPrincipal() instanceof RuntimePrincipal principal)) {
            return null;
        }
        String tenant = principal.tenantKey();
        return tenant == null || tenant.isBlank() ? null : tenant.trim();
    }

    /**
     * Las corridas del tenant del llamador, las más nuevas primero.
     *
     * <p>El tenant sale del <b>token</b>, igual que al iniciar una corrida, y nunca de un
     * parámetro: un tenant que llega en el request es un tenant que el llamador eligió. Runtime
     * sigue sin autorizar sobre él —no consulta pertenencia, eso es de Auth y del vertical—; acá
     * sólo se usa para scopear la consulta.
     *
     * <p>Un token sin claim de tenant no puede listar, y falla explícito con
     * {@link TenantScopeRequiredException}. Las dos alternativas son peores: devolver todas las
     * corridas es una fuga entre tenants, y devolver una lista vacía miente sobre por qué.
     *
     * <p><b>Consecuencia conocida (TD-TENANCY-001):</b> los tokens de servicio todavía no llevan
     * tenant —eso es W2, con {@code act.tenant}— y hoy el 100% del tráfico real de Runtime es S2S.
     * O sea: este endpoint le responde a un token de usuario (Auth lo emite con {@code tenant}
     * desde V57) y le niega el acceso a un llamador S2S. Y las corridas que ese tráfico S2S ya
     * dejó escritas tienen {@code tenant_id} NULL, así que <b>no las va a devolver ninguna
     * consulta scopeada</b>. Se cuentan en {@code runtime_tenancy_missing_claim_total}.
     */
    public Mono<List<Run>> listRuns(int limit, Instant createdBefore, UUID beforeId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Optional::ofNullable)
                .defaultIfEmpty(Optional.empty())
                .flatMap(maybeAuth -> listRunsInternal(maybeAuth.orElse(null), limit, createdBefore, beforeId));
    }

    private Mono<List<Run>> listRunsInternal(
            Authentication caller, int limit, Instant createdBefore, UUID beforeId) {
        String tenantId = tenantOf(caller);
        if (tenantId == null) {
            // A propósito sin métrica: `runtime_tenancy_missing_claim_total` cuenta CORRIDAS
            // INICIADAS sin claim, y su contrato dice que tiene que caer a cero cuando W2
            // despliegue `act.tenant`. Sumarle una denegación de lectura rompería esa lectura y
            // le agregaría un `workflowId` que no es un workflow. El 403 ya se ve en las métricas
            // HTTP.
            return Mono.error(
                    new TenantScopeRequiredException(
                            "el token del llamador no afirma un tenant: no se puede listar corridas"
                                    + " sin scope"));
        }
        return Mono.fromCallable(() -> store.listRuns(tenantId, limit, createdBefore, beforeId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Run> startRun(StartRunRequest request) {
        Timer.Sample sample = metrics.startRunTimer();
        // Phase-1 JWT pass-through: capture the inbound caller's Authentication HERE, while
        // we are still inside the controller's request Reactor Context (populated by
        // RuntimeJwtAuthenticationWebFilter). Once we descend into Mono.fromCallable below
        // and from there imperatively into WorkflowRouter / DefinitionDrivenWorkflowExecutor
        // — which fire-and-forget .subscribe() the workflow Mono — there is no longer any
        // upstream Context to read from. Pass the Authentication forward so the executor can
        // re-attach it via contextWrite(ReactiveSecurityContextHolder.withAuthentication(...))
        // before subscribing the workflow chain. Without this hop, outbound WebClient filters
        // (e.g. cryptobotWebClient's jwtPropagationFilter) see Context.empty() and forward
        // requests with no Authorization header, producing 401s.
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Optional::ofNullable)
                .defaultIfEmpty(Optional.empty())
                .flatMap(maybeAuth -> startRunInternal(request, maybeAuth.orElse(null), sample));
    }

    private Mono<Run> startRunInternal(StartRunRequest request, Authentication caller, Timer.Sample sample) {
        return Mono.fromCallable(
                        () -> {
                            Instant now = Instant.now();
                            UUID runId = UUID.randomUUID();
                            String workflowId = request.workflowId().trim();
                            String correlationId =
                                    request.correlationId() != null && !request.correlationId().isBlank()
                                            ? request.correlationId().trim()
                                            : "corr-" + runId;
                            String input = request.input().trim();
                            Map<String, Object> metadata = new HashMap<>(request.metadata());
                            metadata.put("input", input);
                            // El executor se resuelve ANTES de construir el Run: `Run` copia el
                            // mapa al construirse, así que mutarlo después no lo cambia. Y validar
                            // acá hace que un workflowId inexistente falle sin dejar rastro en el
                            // log, en vez de dejar un run que arrancó y nunca termina.
                            String executor = workflowRouter.resolveExecutorLabel(workflowId);
                            metadata.put("executor", executor);

                            // El tenant sale del TOKEN, nunca de request.metadata(): un tenant que
                            // llega en el cuerpo es un tenant que el llamador eligió. Si el token no
                            // lo trae —hoy, todos los S2S— queda null y se cuenta, no se inventa.
                            String tenantId = tenantOf(caller);
                            if (tenantId == null) {
                                metrics.recordMissingTenantClaim(workflowId);
                            }

                            Run run =
                                    new Run(
                                            runId,
                                            RunStatus.QUEUED,
                                            workflowId,
                                            correlationId,
                                            tenantId,
                                            now,
                                            now,
                                            null,
                                            null,
                                            metadata);
                            // El alta va sin evento y no es excepción a ADR-RT-003:
                            // `runtime_event.run_id` referencia a `runtime_run(id)`, así que un
                            // evento previo a la creación es físicamente imposible. La invariante
                            // gobierna las TRANSICIONES, no el alta.
                            store.saveRun(run);

                            // QUEUED → RUNNING con su evento, en una sola transacción. No hay
                            // ningún saveRun después de lanzar el workflow: esa escritura podía
                            // pisar un estado terminal ya alcanzado —un agente inexistente falla
                            // apenas se suscribe— y devolverlo a RUNNING sin evento que lo
                            // explicara, falsificando la invariante I4.
                            Run running = run.withStatus(RunStatus.RUNNING, Instant.now()).withStartedAt(now);
                            RuntimeEvent startedEvent =
                                    RuntimeEvent.of(
                                            runId,
                                            EventType.RUN_STARTED.wireName(),
                                            Map.of("workflowId", workflowId, "correlationId", correlationId),
                                            false);
                            eventPublisher.publish(store.appendEventAndSaveRun(startedEvent, running));

                            workflowRouter.start(workflowId, runId, input, correlationId, caller);

                            RunLogContext.put(correlationId, runId.toString(), workflowId);
                            try {
                                metrics.recordRunStarted(workflowId);
                                log.info(
                                        "Run started runId={} workflowId={} correlationId={} executor={}",
                                        runId,
                                        workflowId,
                                        correlationId,
                                        executor);
                                return store.findRun(runId).orElse(running);
                            } finally {
                                RunLogContext.clearRun();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(
                        run ->
                                metrics.stopRunTimer(
                                        sample, run.workflowId(), run.status().name()));
    }

    public Mono<RunDetailResponse> getRunDetail(UUID runId) {
        io.micrometer.core.instrument.Timer.Sample projection = metrics.startProjectionTimer();
        return Mono.fromCallable(
                        () -> {
                            Run run =
                                    store.findRun(runId)
                                            .orElseThrow(() -> new RunNotFoundException(runId));
                            return new RunDetailResponse(
                                    run,
                                    store.agentStagesFor(runId),
                                    store.llmCallRecordsFor(runId),
                                    store.eventsFor(runId));
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> metrics.stopProjectionTimer(projection));
    }

    public Mono<Run> getRun(UUID runId) {
        return getRunDetail(runId).map(RunDetailResponse::run);
    }

    public Mono<Run> cancelRun(UUID runId) {
        return Mono.fromCallable(
                        () -> {
                            Run run =
                                    store.findRun(runId)
                                            .orElseThrow(() -> new RunNotFoundException(runId));
                            if (run.status().isTerminal()) {
                                throw new IllegalStateException(
                                        "Run already terminal: " + run.status());
                            }
                            boolean signalled = workflowRouter.requestCancel(run.workflowId(), runId);
                            Instant now = Instant.now();
                            Run cancelled = run.withStatus(RunStatus.CANCELLED, now);
                            Map<String, Object> metadata = new HashMap<>(cancelled.metadata());
                            metadata.put(
                                    "cancelNote",
                                    signalled
                                            ? "Cancellation signalled; in-flight LLM HTTP calls may still complete."
                                            : "Run marked cancelled; no active workflow job found.");
                            Run withNote = cancelled.withMetadata(metadata);
                            store.saveRun(withNote);
                            RuntimeEvent event =
                                    RuntimeEvent.of(
                                            runId,
                                            EventType.RUN_CANCELLED.wireName(),
                                            Map.of(
                                                    "reason",
                                                    "operator_cancel",
                                                    "workflowId",
                                                    run.workflowId(),
                                                    "correlationId",
                                                    run.correlationId()),
                                            true);
                            // Evento y proyección en una sola transacción, el evento primero
                            // (ADR-RT-003). Antes se guardaba el estado y RECIÉN DESPUÉS el
                            // evento: si el proceso moría en el medio, quedaba un run cancelado
                            // que el log no podía explicar.
                            eventPublisher.publish(store.appendEventAndSaveRun(event, withNote));
                            RunLogContext.put(
                                    run.correlationId(), runId.toString(), run.workflowId());
                            try {
                                metrics.recordRunTerminal(run.workflowId(), RunStatus.CANCELLED.name());
                                log.info(
                                        "Run cancelled runId={} workflowId={} correlationId={}",
                                        runId,
                                        run.workflowId(),
                                        run.correlationId());
                                return withNote;
                            } finally {
                                RunLogContext.clearRun();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

}
