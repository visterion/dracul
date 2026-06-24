package de.visterion.dracul.hunting.finnhub;

import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.strigoi.echo.EarningsObservation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Finnhub /calendar/earnings -> EarningsObservation. Free tier: 60 calls/min, US coverage. */
@Component
public class FinnhubEarningsAdapter {

    private final RestClient http;
    private final String apiKey;

    @Autowired
    public FinnhubEarningsAdapter(
            @Value("${dracul.finnhub.api-key:}") String apiKey,
            @Value("${dracul.finnhub.base-url:https://finnhub.io/api/v1}") String baseUrl) {
        this.apiKey = apiKey;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    // Test constructor.
    FinnhubEarningsAdapter(RestClient http, String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    public DataSourceResult<EarningsObservation> recent(LocalDate from, LocalDate to) {
        if (apiKey == null || apiKey.isBlank()) {
            return DataSourceResult.unavailable("finnhub", "finnhub: no api key");
        }
        JsonNode body;
        try {
            body = http.get()
                    .uri(uri -> uri.path("/calendar/earnings")
                            .queryParam("from", from.toString())
                            .queryParam("to", to.toString())
                            .queryParam("token", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            return DataSourceResult.unavailable("finnhub", "finnhub: " + e.getMessage());
        }
        if (body == null) return DataSourceResult.healthy("finnhub", List.of());

        List<EarningsObservation> out = new ArrayList<>();
        for (JsonNode row : body.path("earningsCalendar")) {
            BigDecimal actual = dec(row, "epsActual");
            BigDecimal estimate = dec(row, "epsEstimate");
            if (actual == null || estimate == null) continue;     // unreported / no consensus
            BigDecimal surprisePct = estimate.signum() == 0 ? null
                    : actual.subtract(estimate)
                        .divide(estimate.abs(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            String symbol = row.path("symbol").asText("").toUpperCase();
            if (symbol.isEmpty()) continue;
            LocalDate date;
            try {
                date = LocalDate.parse(row.path("date").asText());
            } catch (Exception e) {
                continue; // skip rows with a missing/malformed date
            }
            out.add(new EarningsObservation(symbol, symbol, date, actual, estimate, surprisePct,
                    dec(row, "revenueActual"), dec(row, "revenueEstimate")));
        }
        return DataSourceResult.healthy("finnhub", out);
    }

    private static BigDecimal dec(JsonNode row, String field) {
        JsonNode n = row.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        try { return new BigDecimal(n.asText()); } catch (NumberFormatException e) { return null; }
    }
}
