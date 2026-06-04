package de.visterion.dracul.hunting.edgar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * SEC EDGAR full-text-search adapter for Form 10-12B spin-off registrations.
 * Metadata-only (no per-filing XML fetch). Graceful degradation: any failure
 * returns an empty list and logs a warning — the bee never dies on an EDGAR
 * hiccup. Tickers are frequently absent on fresh registrations (spin-co not
 * trading yet); such filings are still returned (company-name only).
 */
@Component
public class EdgarSpinoffAdapter {

    private static final Logger log = LoggerFactory.getLogger(EdgarSpinoffAdapter.class);

    private final RestClient http;
    private final String archiveBase;

    @Autowired
    public EdgarSpinoffAdapter(
            @Value("${dracul.edgar.user-agent}") String userAgent,
            @Value("${dracul.edgar.archive-base:https://www.sec.gov}") String archiveBase) {
        this.http = RestClient.builder()
                .baseUrl("https://efts.sec.gov")
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.archiveBase = archiveBase;
    }

    // Test constructor: pre-built RestClient (User-Agent already set) + archive base.
    EdgarSpinoffAdapter(RestClient http, String archiveBase) {
        this.http = http;
        this.archiveBase = archiveBase;
    }

    public List<SpinoffFiling> recentSpinoffs(LocalDate from, LocalDate to) {
        JsonNode search;
        try {
            search = http.get()
                    .uri(uri -> uri.path("/LATEST/search-index")
                            .queryParam("forms", "10-12B")
                            .queryParam("dateRange", "custom")
                            .queryParam("startdt", from.toString())
                            .queryParam("enddt", to.toString())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("EDGAR spin-off search failed: {}", e.getMessage());
            return List.of();
        }
        if (search == null) return List.of();
        JsonNode hits = search.path("hits").path("hits");
        if (!hits.isArray() || hits.isEmpty()) return List.of();

        List<SpinoffFiling> out = new ArrayList<>();
        for (JsonNode hit : hits) {
            try {
                SpinoffFiling f = parseHit(hit);
                if (f != null) out.add(f);
            } catch (Exception e) {
                // skip malformed individual hit
            }
        }
        return out;
    }

    private SpinoffFiling parseHit(JsonNode hit) {
        JsonNode src = hit.path("_source");

        String company = "";
        JsonNode names = src.path("display_names");
        if (names.isArray() && !names.isEmpty()) {
            company = names.get(0).asText("");
            int p = company.indexOf(" (CIK");
            if (p > 0) company = company.substring(0, p).trim();
        }

        String ticker = "";
        JsonNode tn = src.path("tickers");
        if (tn.isArray() && !tn.isEmpty()) ticker = tn.get(0).asText("").toUpperCase();

        LocalDate filingDate;
        try {
            filingDate = LocalDate.parse(src.path("file_date").asText(""));
        } catch (Exception e) {
            return null;
        }
        String formType = src.path("file_type").asText("10-12B");

        String url = "";
        String[] parts = hit.path("_id").asText("").split(":");
        if (parts.length == 2) {
            String accessionNoDashes = parts[0].replace("-", "");
            long cik = Long.parseLong(accessionNoDashes.substring(0, Math.min(10, accessionNoDashes.length())));
            url = archiveBase + "/Archives/edgar/data/" + cik + "/" + accessionNoDashes + "/" + parts[1];
        }

        if (company.isEmpty() && ticker.isEmpty()) return null;
        return new SpinoffFiling(ticker, company, formType, filingDate, url);
    }
}
