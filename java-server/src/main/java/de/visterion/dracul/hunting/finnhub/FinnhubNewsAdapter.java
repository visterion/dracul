package de.visterion.dracul.hunting.finnhub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Finnhub adapter for material company news and analyst recommendation trends.
 * Graceful degradation: a blank API key short-circuits to an empty list (no
 * HTTP), and any error returns an empty list — the Daywalker poll never dies on
 * a Finnhub hiccup. Negativity / downgrade severity is judged later by the LLM;
 * this adapter only surfaces the raw signal.
 */
@Component
public class FinnhubNewsAdapter {

    private static final Logger log = LoggerFactory.getLogger(FinnhubNewsAdapter.class);

    private final RestClient http;
    private final String apiKey;

    @Autowired
    public FinnhubNewsAdapter(
            @Value("${dracul.finnhub.api-key:}") String apiKey,
            @Value("${dracul.finnhub.base-url:https://finnhub.io/api/v1}") String baseUrl) {
        this.apiKey = apiKey;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    // Test constructor: pre-built RestClient + explicit key.
    FinnhubNewsAdapter(RestClient http, String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    public List<NewsHeadline> companyNews(String symbol, LocalDate from, LocalDate to) {
        if (apiKey.isBlank()) return List.of();
        JsonNode arr;
        try {
            arr = http.get()
                    .uri(uri -> uri.path("/company-news")
                            .queryParam("symbol", symbol)
                            .queryParam("from", from.toString())
                            .queryParam("to", to.toString())
                            .queryParam("token", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Finnhub company-news fetch failed for {}: {}", symbol, e.getMessage());
            return List.of();
        }
        if (arr == null || !arr.isArray()) return List.of();
        List<NewsHeadline> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String headline = n.path("headline").asText("");
            if (headline.isBlank()) continue;
            out.add(new NewsHeadline(
                    headline,
                    n.path("summary").asText(""),
                    n.path("source").asText(""),
                    Instant.ofEpochSecond(n.path("datetime").asLong(0)),
                    n.path("url").asText("")));
        }
        return out;
    }

    public List<RecommendationTrend> recommendationTrend(String symbol) {
        if (apiKey.isBlank()) return List.of();
        JsonNode arr;
        try {
            arr = http.get()
                    .uri(uri -> uri.path("/stock/recommendation")
                            .queryParam("symbol", symbol)
                            .queryParam("token", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Finnhub recommendation fetch failed for {}: {}", symbol, e.getMessage());
            return List.of();
        }
        if (arr == null || !arr.isArray()) return List.of();
        List<RecommendationTrend> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(new RecommendationTrend(
                    n.path("period").asText(""),
                    n.path("strongBuy").asInt(0),
                    n.path("buy").asInt(0),
                    n.path("hold").asInt(0),
                    n.path("sell").asInt(0),
                    n.path("strongSell").asInt(0)));
        }
        return out;
    }
}
