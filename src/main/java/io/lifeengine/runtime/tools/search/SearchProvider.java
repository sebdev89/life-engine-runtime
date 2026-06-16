package io.lifeengine.runtime.tools.search;

import java.util.List;
import reactor.core.publisher.Mono;

interface SearchProvider {

    String name();

    boolean isAvailable();

    Mono<List<SearchResult>> search(String query, int maxResults);
}
