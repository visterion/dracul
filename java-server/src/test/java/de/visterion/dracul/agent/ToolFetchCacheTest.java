package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolFetchCacheTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private AgentToolCatalog catalogWith(ToolCatalogEntry... entries) {
        AgentDefaultProvider stub = new AgentDefaultProvider() {
            @Override public AgentDefinition defaultDefinition() { return null; }
            @Override public List<ToolCatalogEntry> catalogEntries() { return List.of(entries); }
        };
        return new AgentToolCatalog(List.of(stub));
    }

    private ToolCatalogEntry entry(String name, boolean cacheable, Integer ttl) {
        return new ToolCatalogEntry(name, "d", json.createObjectNode(), "/p", 30, cacheable, ttl);
    }

    @Test
    void cacheableHitDoesNotRecompute() {
        var cache = new ToolFetchCache(catalogWith(entry("t", true, null)), 300);
        var calls = new AtomicInteger();
        var first = cache.get("t", "k", () -> { calls.incrementAndGet(); return Map.of("v", 1); });
        var second = cache.get("t", "k", () -> { calls.incrementAndGet(); return Map.of("v", 2); });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void nonCacheableAlwaysComputes() {
        var cache = new ToolFetchCache(catalogWith(entry("t", false, null)), 300);
        var calls = new AtomicInteger();
        cache.get("t", "k", () -> { calls.incrementAndGet(); return Map.of("v", 1); });
        cache.get("t", "k", () -> { calls.incrementAndGet(); return Map.of("v", 2); });
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void unknownToolIsNotCached() {
        var cache = new ToolFetchCache(catalogWith(), 300);
        var calls = new AtomicInteger();
        cache.get("missing", "k", () -> { calls.incrementAndGet(); return Map.of("v", 1); });
        cache.get("missing", "k", () -> { calls.incrementAndGet(); return Map.of("v", 1); });
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void globalTtlZeroDisablesCaching() {
        var cache = new ToolFetchCache(catalogWith(entry("t", true, null)), 0);
        var calls = new AtomicInteger();
        cache.get("t", "k", () -> { calls.incrementAndGet(); return Map.of("v", 1); });
        cache.get("t", "k", () -> { calls.incrementAndGet(); return Map.of("v", 1); });
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void perToolTtlOverridesGlobal() {
        var cache = new ToolFetchCache(catalogWith(entry("t", true, 60)), 0);
        var calls = new AtomicInteger();
        cache.get("t", "k", () -> { calls.incrementAndGet(); return Map.of("v", 1); });
        cache.get("t", "k", () -> { calls.incrementAndGet(); return Map.of("v", 1); });
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void differentParamsKeysCachedIndependently() {
        var cache = new ToolFetchCache(catalogWith(entry("t", true, null)), 300);
        var calls = new AtomicInteger();
        cache.get("t", "k1", () -> { calls.incrementAndGet(); return Map.of("v", 1); });
        cache.get("t", "k2", () -> { calls.incrementAndGet(); return Map.of("v", 2); });
        cache.get("t", "k1", () -> { calls.incrementAndGet(); return Map.of("v", 99); });
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void computeExceptionPropagatesAndIsNotCached() {
        var cache = new ToolFetchCache(catalogWith(entry("t", true, null)), 300);
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> cache.get("t", "k", () -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        })).isInstanceOf(RuntimeException.class);
        var ok = cache.get("t", "k", () -> { calls.incrementAndGet(); return Map.of("v", 1); });
        assertThat(calls.get()).isEqualTo(2);
        assertThat(ok).isEqualTo(Map.of("v", 1));
    }

    @Test
    void resultRejectedByPredicateIsNotCached() {
        var cache = new ToolFetchCache(catalogWith(entry("t", true, null)), 300);
        var calls = new AtomicInteger();
        java.util.function.Supplier<Map<String, Object>> compute = () -> {
            calls.incrementAndGet();
            return Map.of("output", Map.of("data_source_health",
                    Map.of("status", "unavailable")));
        };
        java.util.function.Predicate<Map<String, Object>> cacheable =
                p -> "healthy".equals(((java.util.Map<?, ?>) ((java.util.Map<?, ?>) p.get("output"))
                        .get("data_source_health")).get("status"));

        cache.get("t", "k", compute, cacheable);
        cache.get("t", "k", compute, cacheable);

        assertThat(calls.get()).isEqualTo(2);   // not cached → recomputed
    }

    @Test
    void healthyResultIsCached() {
        var cache = new ToolFetchCache(catalogWith(entry("t", true, null)), 300);
        var calls = new AtomicInteger();
        java.util.function.Supplier<Map<String, Object>> compute = () -> {
            calls.incrementAndGet();
            return Map.of("output", Map.of("data_source_health",
                    Map.of("status", "healthy")));
        };
        java.util.function.Predicate<Map<String, Object>> cacheable =
                p -> "healthy".equals(((java.util.Map<?, ?>) ((java.util.Map<?, ?>) p.get("output"))
                        .get("data_source_health")).get("status"));

        cache.get("t", "k", compute, cacheable);
        cache.get("t", "k", compute, cacheable);

        assertThat(calls.get()).isEqualTo(1);   // cached → computed once
    }
}
