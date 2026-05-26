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
public class GetCryptoWatchlistTool implements ToolExecutor {

    public static final String TOOL_ID = "cryptobot.get-crypto-watchlist";

    private final CryptobotServiceClient client;
    private final ObjectMapper mapper;

    public GetCryptoWatchlistTool(CryptobotServiceClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override public String toolId() { return TOOL_ID; }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_ID, "List the active cryptobot watchlist (curated symbols)");
    }

    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionRequest request, WorkflowRunContext ctx) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }
        CryptoToolSupport.emitToolStarted(ctx, TOOL_ID, null);
        long started = System.currentTimeMillis();
        return client.getWatchlist()
                .map(arr -> {
                    String json = CryptoToolSupport.toJson(mapper, arr);
                    ctx.putToolOutput(TOOL_ID, json);
                    CryptoToolSupport.emitToolSucceeded(ctx, TOOL_ID, null, System.currentTimeMillis() - started, json);
                    return ToolExecutionResult.ok(TOOL_ID, json);
                })
                .onErrorResume(ex -> CryptoToolSupport.failTool(ctx, TOOL_ID, ex, System.currentTimeMillis() - started));
    }
}
