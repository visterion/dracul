package de.visterion.dracul.hunting.finnhub;

import de.visterion.dracul.strigoi.echo.EquityMetrics;
import de.visterion.dracul.strigoi.echo.EquityMetricsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Finnhub adapter for echo's SP2 equity metrics: beta + market cap + 52-week range
 * from {@code /stock/metric}, and sector from {@code /stock/profile2}. Graceful: a
 * blank key short-circuits (no HTTP), a metric-call failure → {@link EquityMetrics#unavailable()},
 * a profile failure → metrics with a null sector. The reasoning LLM does the judgement;
 * this adapter only surfaces the raw numbers.
 */
@Component
public class FinnhubEquityMetrics implements EquityMetricsPort {

    private static final Logger log = LoggerFactory.getLogger(FinnhubEquityMetrics.class);

    private final RestClient http;
    private final String apiKey;

    @Autowired
    public FinnhubEquityMetrics(
            @Value("${dracul.finnhub.api-key:}") String apiKey,
            @Value("${dracul.finnhub.base-url:https://finnhub.io/api/v1}") String baseUrl) {
        this.apiKey = apiKey;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    // Test constructor: pre-built RestClient + explicit key.
    FinnhubEquityMetrics(RestClient http, String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    @Override
    public EquityMetrics metrics(String symbol) {
        if (apiKey == null || apiKey.isBlank()) return EquityMetrics.unavailable();
        JsonNode body;
        try {
            body = http.get()
                    .uri(uri -> uri.path("/stock/metric")
                            .queryParam("symbol", symbol)
                            .queryParam("metric", "all")
                            .queryParam("token", apiKey)
                            .build())
                    .retrieve().body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Finnhub equity-metrics fetch failed for {}: {}", symbol, e.getMessage());
            return EquityMetrics.unavailable();
        }
        if (body == null) return EquityMetrics.unavailable();
        JsonNode m = body.path("metric");
        if (m.isMissingNode() || !m.isObject()) return EquityMetrics.unavailable();

        return new EquityMetrics(
                dbl(m, "beta"),
                dbl(m, "marketCapitalization"),
                dbl(m, "52WeekLow"),
                dbl(m, "52WeekHigh"),
                sector(symbol),
                true);
    }

    private String sector(String symbol) {
        try {
            JsonNode p = http.get()
                    .uri(uri -> uri.path("/stock/profile2")
                            .queryParam("symbol", symbol)
                            .queryParam("token", apiKey)
                            .build())
                    .retrieve().body(JsonNode.class);
            if (p == null) return null;
            JsonNode ind = p.path("finnhubIndustry");
            return ind.isTextual() ? ind.asText() : null;
        } catch (Exception e) {
            log.warn("Finnhub profile2 fetch failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private static Double dbl(JsonNode metric, String field) {
        JsonNode n = metric.path(field);
        return n.isNumber() ? n.asDouble() : null;
    }
}
