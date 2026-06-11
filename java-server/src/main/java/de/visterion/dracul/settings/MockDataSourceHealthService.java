package de.visterion.dracul.settings;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Canned data-source health for the dev profile / tests — never hits the network. */
@Component
@Profile("dev")
public class MockDataSourceHealthService implements DataSourceHealthService {

    private static final long CACHE_MS = 60_000;
    private record Cached(Instant at, List<DataSourceHealth> results) {}
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    @Override
    public List<DataSourceHealth> probeAll(boolean refresh) {
        Cached c = cache.get();
        if (!refresh && c != null && Duration.between(c.at(), Instant.now()).toMillis() <= CACHE_MS) {
            return c.results();
        }
        List<DataSourceHealth> results = build(Instant.now().toString());
        cache.set(new Cached(Instant.now(), results));
        return results;
    }

    private static List<DataSourceHealth> build(String now) {
        return List.of(
                new DataSourceHealth("edgar", "SEC EDGAR", true, "ok", 200, null, 142L,
                        List.of("strigoi-spin", "strigoi-insider", "strigoi-merger", "daywalker"), "10 req/s", now),
                new DataSourceHealth("yahoo", "Yahoo Finance", true, "rate_limited", 429, "Too Many Requests", 88L,
                        List.of("strigoi-echo", "daywalker"), "unofficial / scraped", now),
                new DataSourceHealth("finnhub", "Finnhub", true, "ok", 200, null, 210L,
                        List.of("strigoi-lazarus", "daywalker"), "provider-dependent (free tier)", now),
                new DataSourceHealth("wikipedia", "Wikipedia", true, "ok", 200, null, 175L,
                        List.of("strigoi-index"), "MediaWiki UA policy", now));
    }
}
