package io.lifeengine.runtime.ext.cryptomarketreview.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.ext.cryptomarketreview.cryptobot.CryptobotServiceClient;
import io.lifeengine.runtime.tools.ToolDefinition;
import io.lifeengine.runtime.tools.ToolExecutionRequest;
import io.lifeengine.runtime.tools.ToolExecutionResult;
import io.lifeengine.runtime.tools.ToolExecutor;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.crypto-market-review.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class GetCryptoObservationsTool implements ToolExecutor {

    public static final String TOOL_ID = "cryptobot.get-crypto-observations";
    private static final int DEFAULT_LIMIT = 10;

    private final CryptobotServiceClient client;
    private final ObjectMapper mapper;

    public GetCryptoObservationsTool(CryptobotServiceClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override public String toolId() { return TOOL_ID; }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_ID, "Recent market observations (venue snapshots) for a symbol");
    }

    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionRequest request, WorkflowRunContext ctx) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }
        String symbol = CryptoToolSupport.resolveSymbol(mapper, request.input());
        CryptoToolSupport.emitToolStarted(ctx, TOOL_ID, symbol);
        long started = System.currentTimeMillis();
        return client.getObservations(symbol, DEFAULT_LIMIT)
                .map(arr -> {
                    String json = CryptoToolSupport.toJson(mapper, arr);
                    ctx.putToolOutput(TOOL_ID, json);
                    CryptoToolSupport.emitToolSucceeded(ctx, TOOL_ID, symbol, System.currentTimeMillis() - started, json);
                    return ToolExecutionResult.ok(TOOL_ID, json);
                })
                .onErrorResume(ex -> CryptoToolSupport.failTool(ctx, TOOL_ID, ex, System.currentTimeMillis() - started));
    }
}
