package de.visterion.dracul.settings;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Data-source health for the settings panel. After the 7a–7c Agora migration Dracul's only
 *  market-data dependency is Agora, so health = "is Agora reachable" via the ping tool.
 *  Returns a single "agora" row; the DataSourceHealth shape and endpoint are unchanged. */
@Component
@Profile("!dev")
public class AgoraDataSourceHealthService implements DataSourceHealthService {

    private static final long CACHE_MS = 60_000;
    private static final List<String> COVERS = List.of(
            "quotes", "ohlc", "filings", "fundamentals", "earnings", "news", "index", "intraday", "fx");

    private record Cached(Instant at, List<DataSourceHealth> results) {}

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public AgoraDataSourceHealthService(AgoraClient agora) {
        this.agora = agora;
    }

    @Override
    public List<DataSourceHealth> probeAll(boolean refresh) {
        Cached c = cache.get();
        if (!refresh && c != null && Duration.between(c.at(), Instant.now()).toMillis() <= CACHE_MS) {
            return c.results();
        }
        List<DataSourceHealth> results = List.of(probeAgora());
        cache.set(new Cached(Instant.now(), results));
        return results;
    }

    private DataSourceHealth probeAgora() {
        String checkedAt = Instant.now().toString();
        long startNanos = System.nanoTime();
        try {
            agora.callTool("ping", mapper.createObjectNode());
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            return new DataSourceHealth("agora", "Agora", true, "ok",
                    null, null, latencyMs, COVERS, "in-cluster MCP", checkedAt);
        } catch (AgoraUnavailableException e) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String status = DataSourceStatus.classify(null, e);
            return new DataSourceHealth("agora", "Agora", true, status,
                    null, redact(e.getMessage()), latencyMs, COVERS, "in-cluster MCP", checkedAt);
        }
    }

    private static String redact(String msg) {
        if (msg == null) return null;
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
