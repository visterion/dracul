package de.visterion.dracul.strigoi.lazarus;

import tools.jackson.databind.JsonNode;

/**
 * Extracts the Piotroski-near {@link BasicFinancials} subset from the raw provider metrics
 * blob fetched via AgoraCompanyData.fundamentals (Finnhub /stock/metric "metric" keys — the
 * exact keys the deleted FinnhubFundamentalsAdapter parsed). Null-safe: null / non-object
 * input yields null; an absent or non-numeric field yields a null field (never a fake 0).
 */
public final class BasicFinancialsExtractor {

    private BasicFinancialsExtractor() {}

    public static BasicFinancials extract(JsonNode metrics) {
        if (metrics == null || !metrics.isObject()) return null;
        return new BasicFinancials(
                dbl(metrics, "52WeekLow"),
                dbl(metrics, "52WeekHigh"),
                dbl(metrics, "roaTTM"),
                dbl(metrics, "currentRatioQuarterly"),
                dbl(metrics, "totalDebt/totalEquityQuarterly"),
                dbl(metrics, "grossMarginTTM"),
                dbl(metrics, "netProfitMarginTTM"),
                dbl(metrics, "revenueGrowthTTMYoy"),
                dbl(metrics, "epsGrowthTTMYoy"),
                dbl(metrics, "pbAnnual"),
                dbl(metrics, "peTTM"),
                dbl(metrics, "freeCashFlowPerShareTTM"));
    }

    private static Double dbl(JsonNode metric, String field) {
        JsonNode n = metric.path(field);
        return n.isNumber() ? n.asDouble() : null;
    }
}
