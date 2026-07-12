package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * SP2 equity metrics (beta, market cap, 52-week range, sector) extracted from the RAW
 * fundamentals/profile blobs fetched via AgoraCompanyData — the exact Finnhub keys the deleted
 * FinnhubEquityMetrics adapter parsed. Metrics blob missing → unavailable; profile missing →
 * metrics with a null sector.
 */
@Component
public class EquityMetricsExtractor {

    private final AgoraCompanyData companyData;

    public EquityMetricsExtractor(AgoraCompanyData companyData) { this.companyData = companyData; }

    public EquityMetrics metrics(String symbol) {
        JsonNode m = companyData.fundamentals(symbol);
        if (m == null || !m.isObject()) return EquityMetrics.unavailable();
        return new EquityMetrics(
                dbl(m, "beta"),
                dbl(m, "marketCapitalization"),
                dbl(m, "52WeekLow"),
                dbl(m, "52WeekHigh"),
                sector(symbol),
                true);
    }

    /** Like {@link #metrics(String)} but WITHOUT the extra get_company_profile call: sector is
     *  always null. For consumers (insider/index/spin) that read only beta/marketCap/52-week and
     *  never the sector, this saves one remote MCP round-trip per symbol. availability semantics
     *  are identical to metrics(): unavailable iff the fundamentals blob is missing. */
    public EquityMetrics metricsWithoutSector(String symbol) {
        JsonNode m = companyData.fundamentals(symbol);
        if (m == null || !m.isObject()) return EquityMetrics.unavailable();
        return new EquityMetrics(
                dbl(m, "beta"),
                dbl(m, "marketCapitalization"),
                dbl(m, "52WeekLow"),
                dbl(m, "52WeekHigh"),
                null,
                true);
    }

    private String sector(String symbol) {
        JsonNode p = companyData.profile(symbol);
        if (p == null) return null;
        JsonNode ind = p.path("finnhubIndustry");
        return ind.isTextual() ? ind.asText() : null;
    }

    private static Double dbl(JsonNode metric, String field) {
        JsonNode n = metric.path(field);
        return n.isNumber() ? n.asDouble() : null;
    }
}
