package io.lifeengine.runtime.agents;

import io.lifeengine.runtime.api.SecretRedactor;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.llm.LlmCallException;
import io.lifeengine.runtime.llm.LlmCallRecord;
import io.lifeengine.runtime.llm.LlmClient;
import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.llm.LlmRequest;
import io.lifeengine.runtime.llm.LlmResponse;
import io.lifeengine.runtime.llm.LlmRetryConfig;
import io.lifeengine.runtime.prompts.PromptTemplate;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/** Shared LLM call + mandatory LLM_CALL_* runtime events and {@link LlmCallRecord} persistence. */
public final class LlmAgentSupport {

    private static final Logger log = LoggerFactory.getLogger(LlmAgentSupport.class);

    private LlmAgentSupport() {}

    public static Mono<LlmResponse> callLlm(
            WorkflowRunContext ctx,
            String stageId,
            String agentId,
            LlmClient llmClient,
            List<LlmMessage> messages) {
        return callLlm(ctx, stageId, agentId, llmClient, messages, null);
    }

    public static Mono<LlmResponse> callLlm(
            WorkflowRunContext ctx,
            String stageId,
            String agentId,
            LlmClient llmClient,
            List<LlmMessage> messages,
            PromptTemplate template) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }
        String model = llmClient.defaultModel();
        String prompt = messages.stream().map(LlmMessage::content).reduce("", (a, b) -> a + "\n" + b);
        String promptRedacted = SecretRedactor.redact(prompt);
        Instant started = Instant.now();
        UUID callId = UUID.randomUUID();
        log.info(
                "Calling LLM model {} for agent {} runId={} workflowId={} correlationId={} endpoint={}",
                model,
                agentId,
                ctx.runId(),
                ctx.workflowId(),
                ctx.correlationId(),
                llmClient.chatCompletionsEndpoint());
        ctx.emit(
                EventType.LLM_CALL_STARTED,
                withModelRole(
                        WorkflowRunContext.previewAttrs(agentId, model, promptRedacted, template),
                        llmClient),
                false);

        // publishOn(boundedElastic) is the critical hop: WebClient signals on Netty event-loop
        // threads (reactor-http-epoll-N) which implement Reactor's NonBlocking marker. Without
        // this hop, every downstream operator (the retry filter/doBeforeRetry below, every
        // doOnSuccess/onErrorResume here, and every operator in the calling agent's chain) would
        // run on the event loop and any blocking RunStore access via ctx.emit/appendLlmCallRecord
        // would trip Reactor's blocking detector inside R2dbcRunStore.block(...).
        Mono<LlmResponse> chatCall =
                llmClient
                        .chatCompletion(new LlmRequest(model, messages))
                        .publishOn(Schedulers.boundedElastic());
        LlmRetryConfig retry = llmClient.retryConfig();
        if (retry != null && retry.active()) {
            chatCall = chatCall.retryWhen(buildRetrySpec(ctx, agentId, model, retry));
        }

        return chatCall
                .doOnSuccess(
                        response -> {
                            long latencyMs = Math.max(0, Instant.now().toEpochMilli() - started.toEpochMilli());
                            Instant finished = Instant.now();
                            String raw = response.content();
                            ctx.emit(
                                    EventType.LLM_CALL_SUCCEEDED,
                                    withModelRole(
                                            WorkflowRunContext.llmSucceededAttrs(
                                                    agentId,
                                                    model,
                                                    latencyMs,
                                                    raw,
                                                    response.usage(),
                                                    template),
                                            llmClient),
                                    false);
                            ctx.appendLlmCallRecord(
                                    new LlmCallRecord(
                                            callId,
                                            stageId,
                                            agentId,
                                            WorkflowRunContext.LLM_PROVIDER,
                                            model,
                                            WorkflowRunContext.truncate(promptRedacted, 2000),
                                            WorkflowRunContext.truncate(raw, 8000),
                                            null,
                                            null,
                                            started,
                                            finished,
                                            latencyMs,
                                            roleMetadata(llmClient)));
                        })
                .onErrorResume(
                        error -> {
                            long latencyMs = Math.max(0, Instant.now().toEpochMilli() - started.toEpochMilli());
                            Instant finished = Instant.now();
                            if (error instanceof LlmCallException llmEx) {
                                ctx.emit(
                                        EventType.LLM_CALL_FAILED,
                                        withModelRole(
                                                WorkflowRunContext.llmFailedAttrs(
                                                        agentId, model, latencyMs, llmEx),
                                                llmClient),
                                        false);
                                ctx.appendLlmCallRecord(
                                        new LlmCallRecord(
                                                callId,
                                                stageId,
                                                agentId,
                                                WorkflowRunContext.LLM_PROVIDER,
                                                model,
                                                WorkflowRunContext.truncate(promptRedacted, 2000),
                                                llmEx.responseBody() != null
                                                        ? WorkflowRunContext.truncate(
                                                                SecretRedactor.redact(llmEx.responseBody()), 8000)
                                                        : null,
                                                null,
                                                llmEx.getMessage(),
                                                started,
                                                finished,
                                                latencyMs,
                                                roleMetadata(llmClient)));
                                log.error(
                                        "LLM_CALL_FAILED agent={} runId={} workflowId={} status={} endpoint={}",
                                        agentId,
                                        ctx.runId(),
                                        ctx.workflowId(),
                                        llmEx.statusCode(),
                                        llmEx.endpoint(),
                                        error);
                            } else {
                                String message =
                                        error.getMessage() == null ? error.toString() : error.getMessage();
                                ctx.emit(
                                        EventType.LLM_CALL_FAILED,
                                        withModelRole(
                                                WorkflowRunContext.llmFailedAttrs(
                                                        agentId, model, message, latencyMs),
                                                llmClient),
                                        false);
                                ctx.appendLlmCallRecord(
                                        new LlmCallRecord(
                                                callId,
                                                stageId,
                                                agentId,
                                                WorkflowRunContext.LLM_PROVIDER,
                                                model,
                                                WorkflowRunContext.truncate(promptRedacted, 2000),
                                                null,
                                                null,
                                                message,
                                                started,
                                                finished,
                                                latencyMs,
                                                roleMetadata(llmClient)));
                            }
                            return Mono.error(error);
                        });
    }

    /**
     * Rol de Multi-Model V2 que atendió la llamada, para el detalle del run y el cockpit.
     *
     * <p>Va en {@code metadata} y no como componente nuevo del record: {@link LlmCallRecord} ya
     * persiste ese mapa, así que agregar la clave no toca el esquema de la base.
     *
     * <p>Se registra también en los caminos de error. Un fallo sin rol obliga a adivinar si el
     * agente salió por el proveedor que le corresponde o por el {@code @Primary} — que es
     * justamente lo que hay que saber cuando algo falla.
     */
    /**
     * Agrega el rol de Multi-Model V2 a los atributos de un evento {@code LLM_CALL_*}.
     *
     * <p>El rol ya viajaba en {@code metadata} del {@link LlmCallRecord}, pero no en el payload de
     * los eventos: el cockpit arma la lista de llamadas desde el stream SSE y sólo cae al detalle
     * del run cuando ya terminó. Sin esta clave, una corrida en vivo muestra el modelo y esconde
     * por qué ése — que es la mitad que importa cuando un {@code @Qualifier} se cae al
     * {@code @Primary} y los dos caminos resuelven al mismo modelo.
     *
     * <p>La clave es {@code modelRole} y no {@code model_role} a propósito: es la que ya persiste
     * {@code metadata} y la que usan el resto de los atributos del runtime ({@code agentId},
     * {@code latencyMs}, {@code promptTemplateId}). El {@code model_role} en snake_case existe
     * sólo como tag de Prometheus, donde es la convención correcta.
     */
    private static Map<String, String> withModelRole(Map<String, String> attrs, LlmClient llmClient) {
        Map<String, String> role = roleMetadata(llmClient);
        if (role.isEmpty()) {
            return attrs;
        }
        Map<String, String> merged = new LinkedHashMap<>(attrs);
        merged.putAll(role);
        return merged;
    }

    private static Map<String, String> roleMetadata(LlmClient llmClient) {
        String role = llmClient.role();
        return role == null || role.isBlank() ? Map.of() : Map.of("modelRole", role);
    }

    private static Retry buildRetrySpec(
            WorkflowRunContext ctx, String agentId, String model, LlmRetryConfig retry) {
        // Use Retry.max (synchronous, no scheduler) when no backoff is requested. Retry.fixedDelay
        // with Duration.ZERO routes through Mono.delay on Schedulers.parallel(), which behaves
        // unreliably in tests; the synchronous variant matches the user intent for "fast retry".
        long maxAttempts = retry.maxAttempts();
        if (retry.backoffMillis() <= 0L) {
            return Retry.max(maxAttempts)
                    .filter(LlmAgentSupport::isTransientLlmFailure)
                    .doBeforeRetry(signal -> emitRetryWarning(ctx, agentId, model, signal, maxAttempts))
                    .onRetryExhaustedThrow((spec, signal) -> signal.failure());
        }
        return Retry.fixedDelay(maxAttempts, Duration.ofMillis(retry.backoffMillis()))
                .filter(LlmAgentSupport::isTransientLlmFailure)
                .doBeforeRetry(signal -> emitRetryWarning(ctx, agentId, model, signal, maxAttempts))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private static boolean isTransientLlmFailure(Throwable t) {
        return t instanceof LlmCallException llmEx && llmEx.isTransient();
    }

    private static void emitRetryWarning(
            WorkflowRunContext ctx,
            String agentId,
            String model,
            Retry.RetrySignal signal,
            long maxAttempts) {
        emitRetryWarning(ctx, agentId, model, signal.totalRetries(), maxAttempts, signal.failure());
    }

    private static void emitRetryWarning(
            WorkflowRunContext ctx,
            String agentId,
            String model,
            long previousRetries,
            long maxAttempts,
            Throwable failure) {
        long attemptNumber = previousRetries + 1;
        String reason = failure == null ? "unknown" : failure.getClass().getSimpleName();
        Integer status = failure instanceof LlmCallException ex ? ex.statusCode() : null;
        String detail = failure == null ? "" : failure.getMessage();
        String message =
                String.format(
                        "LLM transient failure, retry %d/%d for agent=%s model=%s reason=%s%s: %s",
                        attemptNumber,
                        maxAttempts,
                        agentId,
                        model,
                        reason,
                        status == null ? "" : " status=" + status,
                        detail == null ? "" : detail);
        ctx.addWarning(message);
        log.warn(
                "LLM transient failure, retry {}/{} agent={} runId={} workflowId={} status={} reason={}",
                attemptNumber,
                maxAttempts,
                agentId,
                ctx.runId(),
                ctx.workflowId(),
                status,
                reason,
                failure);
    }
}
