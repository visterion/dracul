package de.visterion.dracul.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Probes each market-data source with one minimal representative request, concurrently,
 * with a per-probe timeout and a short result cache so repeated opens / multiple tabs do
 * not re-probe and worsen rate limits. One probe per SOURCE (the 7 adapter classes map to
 * 4 sources). Lives in its own bean (not the adapters) so the safeguards stay in one place.
 */
@Component
@Profile("!dev")
public class HttpDataSourceHealthService implements DataSourceHealthService {

    private static final long CACHE_MS = 60_000;
    private static final long PROBE_TIMEOUT_MS = 4_000;

    private record Source(String id, String label, String probePath, List<String> usedBy, String rateLimitNote) {}

    // Probe paths are cheap, representative calls. Symbols are arbitrary liquid tickers.
    private final List<Source> sources;
    private final RestClient edgar, yahoo, finnhub, wikipedia;
    private final String finnhubKey;

    private final AtomicReference<Cached> cache = new AtomicReference<>();
    private record Cached(Instant at, List<DataSourceHealth> results) {}

    public HttpDataSourceHealthService(
            @Value("${dracul.edgar.user-agent}") String edgarUserAgent,
            @Value("${dracul.edgar.efts-base:https://efts.sec.gov}") String edgarBase,
            @Value("${dracul.yahoo.base-url:https://query1.finance.yahoo.com}") String yahooBase,
            @Value("${dracul.finnhub.base-url:https://finnhub.io/api/v1}") String finnhubBase,
            @Value("${dracul.finnhub.api-key:}") String finnhubKey,
            @Value("${dracul.wikipedia.base-url:https://en.wikipedia.org}") String wikipediaBase,
            @Value("${dracul.wikipedia.user-agent:Dracul/1.0 (research)}") String wikipediaUserAgent) {
        this.finnhubKey = finnhubKey;
        this.edgar = timedBuilder(edgarBase).defaultHeader("User-Agent", edgarUserAgent).build();
        this.yahoo = timedBuilder(yahooBase).build();
        this.finnhub = timedBuilder(finnhubBase).build();
        this.wikipedia = timedBuilder(wikipediaBase).defaultHeader("User-Agent", wikipediaUserAgent).build();
        this.sources = List.of(
                new Source("edgar", "SEC EDGAR",
                        "/LATEST/search-index?q=test", List.of("strigoi-spin", "strigoi-insider", "strigoi-merger", "daywalker"), "10 req/s"),
                new Source("yahoo", "Yahoo Finance",
                        "/v8/finance/chart/AAPL", List.of("strigoi-echo", "daywalker"), "unofficial / scraped"),
                new Source("finnhub", "Finnhub",
                        "/quote?symbol=AAPL", List.of("strigoi-lazarus", "daywalker"), "provider-dependent (free tier)"),
                new Source("wikipedia", "Wikipedia",
                        "/w/api.php?action=query&meta=siteinfo&format=json", List.of("strigoi-index"), "MediaWiki UA policy"));
    }

    @Override
    public List<DataSourceHealth> probeAll(boolean refresh) {
        Cached c = cache.get();
        if (!refresh && c != null && Duration.between(c.at(), Instant.now()).toMillis() <= CACHE_MS) {
            return c.results();
        }
        List<DataSourceHealth> results = doProbeAll();
        cache.set(new Cached(Instant.now(), results));
        return results;
    }

    private List<DataSourceHealth> doProbeAll() {
        Instant now = Instant.now();
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<DataSourceHealth>> futures = sources.stream()
                    .map(src -> CompletableFuture
                            .supplyAsync(() -> probeOne(src, now), ex)
                            .completeOnTimeout(
                                    health(src, true, "timeout", null, "probe exceeded " + PROBE_TIMEOUT_MS + "ms", PROBE_TIMEOUT_MS, now),
                                    PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    private DataSourceHealth probeOne(Source src, Instant now) {
        if ("finnhub".equals(src.id()) && finnhubKey.isBlank()) {
            return health(src, false, "not_configured", null, "FINNHUB_API_KEY not set", null, now);
        }
        RestClient client = switch (src.id()) {
            case "edgar" -> edgar;
            case "yahoo" -> yahoo;
            case "finnhub" -> finnhub;
            default -> wikipedia;
        };
        String path = src.probePath();
        if ("finnhub".equals(src.id())) {
            path = path + "&token=" + finnhubKey;
        }
        long t0 = System.nanoTime();
        try {
            int code = client.get().uri(path)
                    .exchange((req, res) -> res.getStatusCode().value());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return health(src, true, DataSourceStatus.classify(code, null), code, null, ms, now);
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return health(src, true, DataSourceStatus.classify(null, e), null, redact(e.getMessage()), ms, now);
        }
    }

    private static RestClient.Builder timedBuilder(String baseUrl) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(3_500))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(3_500));
        return RestClient.builder().requestFactory(factory).baseUrl(baseUrl);
    }

    private String redact(String message) {
        if (message == null || finnhubKey.isBlank()) return message;
        return message.replace(finnhubKey, "***");
    }

    private static DataSourceHealth health(Source s, boolean configured, String status,
                                           Integer httpStatus, String detail, Long latencyMs, Instant now) {
        return new DataSourceHealth(s.id(), s.label(), configured, status, httpStatus, detail,
                latencyMs, s.usedBy(), s.rateLimitNote(), now.toString());
    }
}
