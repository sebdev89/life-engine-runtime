package io.lifeengine.runtime.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class TavilySearchProvider implements SearchProvider {

    private final WebClient webClient;
    private final String apiKey;
    private final Duration timeout;

    TavilySearchProvider(WebClient webClient, String apiKey, Duration timeout) {
        this.webClient = webClient;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public boolean isAvailable() {
        return !apiKey.isBlank();
    }

    @Override
    public Mono<List<SearchResult>> search(String query, int maxResults) {
        Map<String, Object> body = Map.of(
                "api_key", apiKey,
                "query", query,
                "max_results", maxResults,
                "search_depth", "basic",
                "include_answer", false,
                "include_images", false,
                "include_raw_content", false);

        return webClient.post()
                .uri("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(this::mapResults)
                .onErrorResume(ex -> Mono.error(new SearchProviderException("tavily call failed: " + ex.getMessage(), ex)));
    }

    private List<SearchResult> mapResults(JsonNode response) {
        List<SearchResult> results = new ArrayList<>();
        JsonNode arr = response.get("results");
        if (arr == null || !arr.isArray()) {
            return results;
        }
        for (JsonNode item : arr) {
            String title = textOrEmpty(item, "title");
            String url = textOrEmpty(item, "url");
            String snippet = textOrEmpty(item, "content");
            results.add(new SearchResult(title, url, snippet));
        }
        return results;
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : "";
    }
}
