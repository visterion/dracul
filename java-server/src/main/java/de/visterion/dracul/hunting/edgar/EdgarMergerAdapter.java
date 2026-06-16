package de.visterion.dracul.hunting.edgar;

import de.visterion.dracul.hunting.DataSourceResult;
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
 * SEC EDGAR full-text-search adapter for merger deal filings: DEFM14A definitive
 * merger proxies and SC TO-T third-party tender offers. Metadata-only (no
 * per-filing XML fetch). Graceful degradation: any failure returns an empty list
 * and logs a warning — the bee never dies on an EDGAR hiccup. Deal targets
 * generally have a tradeable ticker; a hit with neither company nor ticker is
 * dropped.
 */
@Component
public class EdgarMergerAdapter {

    private static final Logger log = LoggerFactory.getLogger(EdgarMergerAdapter.class);

    private final RestClient http;
    private final String archiveBase;

    @Autowired
    public EdgarMergerAdapter(
            @Value("${dracul.edgar.user-agent}") String userAgent,
            @Value("${dracul.edgar.archive-base:https://www.sec.gov}") String archiveBase) {
        this.http = RestClient.builder()
                .baseUrl("https://efts.sec.gov")
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.archiveBase = archiveBase;
    }

    // Test constructor: pre-built RestClient (User-Agent already set) + archive base.
    EdgarMergerAdapter(RestClient http, String archiveBase) {
        this.http = http;
        this.archiveBase = archiveBase;
    }

    public DataSourceResult<MergerFiling> recentDeals(LocalDate from, LocalDate to) {
        JsonNode search;
        try {
            search = http.get()
                    .uri(uri -> uri.path("/LATEST/search-index")
                            .queryParam("forms", "DEFM14A,SC TO-T")
                            .queryParam("dateRange", "custom")
                            .queryParam("startdt", from.toString())
                            .queryParam("enddt", to.toString())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("EDGAR merger search failed: {}", e.getMessage());
            return DataSourceResult.unavailable("edgar", "edgar: " + e.getMessage());
        }
        if (search == null) return DataSourceResult.healthy("edgar", List.of());
        JsonNode hits = search.path("hits").path("hits");
        if (!hits.isArray() || hits.isEmpty()) return DataSourceResult.healthy("edgar", List.of());

        List<MergerFiling> out = new ArrayList<>();
        for (JsonNode hit : hits) {
            try {
                MergerFiling f = parseHit(hit);
                if (f != null) out.add(f);
            } catch (Exception e) {
                // skip malformed individual hit
            }
        }
        return DataSourceResult.healthy("edgar", out);
    }

    private MergerFiling parseHit(JsonNode hit) {
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
        String formType = src.path("file_type").asText("DEFM14A");

        String url = "";
        String[] parts = hit.path("_id").asText("").split(":");
        if (parts.length == 2) {
            String accessionNoDashes = parts[0].replace("-", "");
            long cik = Long.parseLong(accessionNoDashes.substring(0, Math.min(10, accessionNoDashes.length())));
            url = archiveBase + "/Archives/edgar/data/" + cik + "/" + accessionNoDashes + "/" + parts[1];
        }

        if (company.isEmpty() && ticker.isEmpty()) return null;
        return new MergerFiling(ticker, company, formType, filingDate, url);
    }
}
