package de.visterion.dracul.hunting.yahoo;

import de.visterion.dracul.hunting.DataSourceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Yahoo earnings-calendar adapter. The endpoint is unofficial and unstable, so
 * every failure degrades to an empty list (after one retry) — the bee must never
 * die on a Yahoo hiccup. Parses Yahoo's documented field names.
 */
@Component
public class YahooEarningsAdapter {

    private static final Logger log = LoggerFactory.getLogger(YahooEarningsAdapter.class);

    private final RestClient client;

    public YahooEarningsAdapter(RestClient yahooRestClient) {
        this.client = yahooRestClient;
    }

    public DataSourceResult<EarningsEvent> recentEarnings(LocalDate from, LocalDate to) {
        JsonNode body = fetchWithRetry(from, to);
        if (body == null) return DataSourceResult.unavailable("yahoo", "yahoo: fetch failed after retries");
        JsonNode rows = body.path("rows");
        if (!rows.isArray() || rows.isEmpty()) return DataSourceResult.healthy("yahoo", List.of());
        List<EarningsEvent> out = new ArrayList<>();
        for (JsonNode row : rows) {
            EarningsEvent ev = parseRow(row);
            if (ev != null) out.add(ev);
        }
        return DataSourceResult.healthy("yahoo", out);
    }

    private JsonNode fetchWithRetry(LocalDate from, LocalDate to) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return client.get()
                        .uri(uri -> uri.path("/v1/finance/calendar/earnings")
                                .queryParam("startdt", from.toString())
                                .queryParam("enddt", to.toString())
                                .build())
                        .retrieve()
                        .body(JsonNode.class);
            } catch (Exception e) {
                log.warn("Yahoo earnings-calendar fetch attempt {} failed: {}",
                        attempt, e.getMessage());
            }
        }
        return null;
    }

    private EarningsEvent parseRow(JsonNode row) {
        String symbol = row.path("ticker").asText("").toUpperCase();
        if (symbol.isEmpty()) return null;
        String name = row.path("companyshortname").asText(symbol);
        String dt = row.path("startdatetime").asText("");
        if (dt.length() < 10) return null;
        LocalDate reportDate;
        try {
            reportDate = LocalDate.parse(dt.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
        return new EarningsEvent(
                symbol, name, reportDate,
                decimalOrNull(row, "epsactual"),
                decimalOrNull(row, "epsestimate"),
                decimalOrNull(row, "epssurprisepct"));
    }

    private static BigDecimal decimalOrNull(JsonNode row, String field) {
        JsonNode n = row.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        String s = n.asText("");
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
