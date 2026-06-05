package de.visterion.dracul.hunting.finnhub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Finnhub adapter for basic financials (the "metric" object): 52-week range plus
 * the Piotroski-near health ratios. Graceful degradation: a blank API key
 * short-circuits to null (no HTTP), and any error returns null — the nightly
 * Lazarus run degrades to fewer candidates, never crashes. The reasoning LLM
 * does the fundamental judgement; this adapter only surfaces the raw metrics.
 */
@Component
public class FinnhubFundamentalsAdapter {

    private static final Logger log = LoggerFactory.getLogger(FinnhubFundamentalsAdapter.class);

    private final RestClient http;
    private final String apiKey;

    @Autowired
    public FinnhubFundamentalsAdapter(
            @Value("${dracul.finnhub.api-key:}") String apiKey,
            @Value("${dracul.finnhub.base-url:https://finnhub.io/api/v1}") String baseUrl) {
        this.apiKey = apiKey;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    // Test constructor: pre-built RestClient + explicit key.
    FinnhubFundamentalsAdapter(RestClient http, String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    public BasicFinancials basicFinancials(String symbol) {
        if (apiKey.isBlank()) return null;
        JsonNode body;
        try {
            body = http.get()
                    .uri(uri -> uri.path("/stock/metric")
                            .queryParam("symbol", symbol)
                            .queryParam("metric", "all")
                            .queryParam("token", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Finnhub basic-financials fetch failed for {}: {}", symbol, e.getMessage());
            return null;
        }
        if (body == null) return null;
        JsonNode m = body.path("metric");
        if (m.isMissingNode() || !m.isObject()) return null;
        return new BasicFinancials(
                dbl(m, "52WeekLow"),
                dbl(m, "52WeekHigh"),
                dbl(m, "roaTTM"),
                dbl(m, "currentRatioQuarterly"),
                dbl(m, "totalDebt/totalEquityQuarterly"),
                dbl(m, "grossMarginTTM"),
                dbl(m, "netProfitMarginTTM"),
                dbl(m, "revenueGrowthTTMYoy"),
                dbl(m, "epsGrowthTTMYoy"),
                dbl(m, "pbAnnual"),
                dbl(m, "peTTM"),
                dbl(m, "freeCashFlowPerShareTTM"));
    }

    private static Double dbl(JsonNode metric, String field) {
        JsonNode n = metric.path(field);
        return n.isNumber() ? n.asDouble() : null;
    }
}
