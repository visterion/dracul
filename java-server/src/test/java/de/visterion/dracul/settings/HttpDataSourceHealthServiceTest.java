package de.visterion.dracul.settings;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class HttpDataSourceHealthServiceTest {

    HttpServer server;
    String base;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Yahoo's probe path contains "chart" → return 429; everything else → 200.
        server.createContext("/", ex -> {
            int code = ex.getRequestURI().getPath().contains("chart") ? 429 : 200;
            ex.sendResponseHeaders(code, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    private HttpDataSourceHealthService newService(String finnhubKey) {
        // All four sources point at the same stub server; finnhub key non-blank so it probes.
        return new HttpDataSourceHealthService(
                "dracul-test/1.0 test@example.com", // edgar user-agent
                base,   // edgar efts base
                base,   // yahoo base
                base,   // finnhub base
                finnhubKey,
                base,   // wikipedia base
                "Dracul-test/1.0");  // wikipedia user-agent
    }

    private Map<String, DataSourceHealth> byId(java.util.List<DataSourceHealth> rows) {
        return rows.stream().collect(Collectors.toMap(DataSourceHealth::id, r -> r));
    }

    @Test void probesAllFourSourcesAndClassifies() {
        var rows = newService("testkey").probeAll(true);
        assertThat(rows).hasSize(4);
        var m = byId(rows);
        assertThat(m.get("edgar").status()).isEqualTo("ok");
        assertThat(m.get("wikipedia").status()).isEqualTo("ok");
        assertThat(m.get("finnhub").status()).isEqualTo("ok");
        assertThat(m.get("finnhub").configured()).isTrue();
        assertThat(m.get("yahoo").status()).isEqualTo("rate_limited");
        assertThat(m.get("yahoo").httpStatus()).isEqualTo(429);
        assertThat(m.get("edgar").latencyMs()).isNotNull();
    }

    @Test void blankFinnhubKeyIsNotConfiguredWithoutCall() {
        var m = byId(newService("").probeAll(true));
        assertThat(m.get("finnhub").configured()).isFalse();
        assertThat(m.get("finnhub").status()).isEqualTo("not_configured");
        assertThat(m.get("finnhub").httpStatus()).isNull();
    }

    @Test void cachesUnlessRefresh() {
        var svc = newService("testkey");
        var first = svc.probeAll(false);
        var second = svc.probeAll(false);            // cache hit → identical checkedAt
        assertThat(second.get(0).checkedAt()).isEqualTo(first.get(0).checkedAt());
        var refreshed = svc.probeAll(true);          // bypass → newer checkedAt
        assertThat(refreshed.get(0).checkedAt()).isNotEqualTo(first.get(0).checkedAt());
    }
}
