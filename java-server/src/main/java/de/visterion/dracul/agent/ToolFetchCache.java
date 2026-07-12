package de.visterion.dracul.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * TTL cache for tool-fetch webhook results, keyed by {@code toolName + ":" + paramsKey}.
 * Avoids re-hitting upstream providers (EDGAR / Yahoo / market-data) on repeated tool
 * calls within a run or quick re-triggers. Per-tool caching is controlled by the
 * {@link AgentToolCatalog} entry ({@code cacheable} / {@code cacheTtlSeconds}); the
 * global default TTL applies when a cacheable tool has no per-tool TTL.
 */
@Component
public class ToolFetchCache {

    private final AgentToolCatalog catalog;
    private final long globalTtlNanos;
    private final ConcurrentHashMap<String, Cached> store = new ConcurrentHashMap<>();

    private record Cached(Map<String, Object> payload, long expiresAtNanos) {}

    public ToolFetchCache(AgentToolCatalog catalog,
                          @Value("${dracul.agent.tool-fetch.cache-ttl-seconds:300}") long globalTtlSeconds) {
        this.catalog = catalog;
        this.globalTtlNanos = Math.max(0, globalTtlSeconds) * 1_000_000_000L;
    }

    /** Returns the cached payload for (toolName, paramsKey) if fresh; otherwise computes,
     *  stores (when the tool is cacheable with ttl &gt; 0) and returns.
     *  All computed results are cached (equivalent to passing {@code p -> true} as predicate). */
    public Map<String, Object> get(String toolName, String paramsKey,
                                   Supplier<Map<String, Object>> compute) {
        return get(toolName, paramsKey, compute, p -> true);
    }

    /** Returns the cached payload for (toolName, paramsKey) if fresh; otherwise computes
     *  and, if {@code shouldCache} returns {@code true} for the result, stores it.
     *  If the predicate returns {@code false} the result is returned but not cached, so the
     *  next call will recompute (useful for transient failures such as
     *  {@code data_source_health.status == "unavailable"}). */
    public Map<String, Object> get(String toolName, String paramsKey,
                                   Supplier<Map<String, Object>> compute,
                                   Predicate<Map<String, Object>> shouldCache) {
        long ttlNanos = resolveTtlNanos(toolName);
        if (ttlNanos <= 0) {
            return compute.get();
        }
        String key = toolName + ":" + paramsKey;
        long now = System.nanoTime();
        Cached cached = store.get(key);
        if (cached != null && now < cached.expiresAtNanos()) {
            return cached.payload();
        }
        Map<String, Object> payload = compute.get();
        if (shouldCache.test(payload)) {
            store.put(key, new Cached(payload, now + ttlNanos));
        }
        return payload;
    }

    /** Drops all cached entries. Intended for test isolation (the bean is a singleton shared
     *  across test methods in a Spring context); production code relies on TTL expiry instead. */
    public void clear() {
        store.clear();
    }

    private long resolveTtlNanos(String toolName) {
        ToolCatalogEntry entry = catalog.find(toolName).orElse(null);
        if (entry == null || !entry.cacheable()) {
            return 0;
        }
        if (entry.cacheTtlSeconds() != null) {
            return Math.max(0, entry.cacheTtlSeconds()) * 1_000_000_000L;
        }
        return globalTtlNanos;
    }
}
