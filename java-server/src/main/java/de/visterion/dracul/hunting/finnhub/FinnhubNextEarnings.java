package de.visterion.dracul.hunting.finnhub;

import de.visterion.dracul.strigoi.echo.NextEarningsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Next scheduled earnings date from Finnhub {@code /calendar/earnings} over a 90-day forward
 * window (symbol-scoped). Returns the earliest date strictly after today. Graceful: blank key
 * or any error → empty, never throws.
 */
@Component
public class FinnhubNextEarnings implements NextEarningsPort {

    private static final Logger log = LoggerFactory.getLogger(FinnhubNextEarnings.class);
    private static final int FORWARD_DAYS = 90;

    private final RestClient http;
    private final String apiKey;

    @Autowired
    public FinnhubNextEarnings(
            @Value("${dracul.finnhub.api-key:}") String apiKey,
            @Value("${dracul.finnhub.base-url:https://finnhub.io/api/v1}") String baseUrl) {
        this.apiKey = apiKey;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    // Test constructor.
    FinnhubNextEarnings(RestClient http, String apiKey) { this.http = http; this.apiKey = apiKey; }

    @Override
    public Optional<LocalDate> nextEarningsDate(String symbol) {
        if (apiKey == null || apiKey.isBlank()) return Optional.empty();
        LocalDate today = LocalDate.now();
        LocalDate to = today.plusDays(FORWARD_DAYS);
        JsonNode body;
        try {
            body = http.get()
                    .uri(uri -> uri.path("/calendar/earnings")
                            .queryParam("symbol", symbol)
                            .queryParam("from", today.toString())
                            .queryParam("to", to.toString())
                            .queryParam("token", apiKey)
                            .build())
                    .retrieve().body(JsonNode.class);
        } catch (Exception e) {
            log.debug("Finnhub next-earnings fetch failed for {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
        if (body == null) return Optional.empty();
        LocalDate best = null;
        for (JsonNode row : body.path("earningsCalendar")) {
            try {
                LocalDate d = LocalDate.parse(row.path("date").asText(""));
                if (d.isAfter(today) && (best == null || d.isBefore(best))) best = d;
            } catch (Exception rowError) {
                // skip malformed row
            }
        }
        return Optional.ofNullable(best);
    }
}
