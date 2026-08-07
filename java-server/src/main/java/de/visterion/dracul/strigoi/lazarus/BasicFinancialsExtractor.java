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
                dbl(metrics, "freeCashFlowPerShareTTM"),
                dbl(metrics, "marketCapitalization"),
                str(metrics, "reportingCurrency"),
                week52RangeUnavailable(metrics));
    }

    /**
     * Agora's group-scoped marker for "the 52-week OHLC source failed while we asked"
     * (agora c89dba7): {@code "52WeekRange": {"available": false, "error": "<reason>"}}, a sibling
     * of {@code 52WeekLow} / {@code 52WeekHigh} inside the metrics blob. It is emitted ONLY on a
     * source failure — an instrument-scoped {@code NOT_FOUND} deliberately carries no marker,
     * because that is a statement about the instrument, not the source.
     *
     * <p>Read defensively as "available is explicitly false": a missing key, a non-object value or
     * anything other than boolean {@code false} means "no failure reported". Until agora c89dba7 is
     * deployed the key is simply never present, and this reads exactly as it always did.
     */
    private static boolean week52RangeUnavailable(JsonNode metrics) {
        JsonNode available = metrics.path("52WeekRange").path("available");
        return available.isBoolean() && !available.asBoolean();
    }

    private static Double dbl(JsonNode metric, String field) {
        JsonNode n = metric.path(field);
        return n.isNumber() ? n.asDouble() : null;
    }

    /** Reporting-currency code (non-US path only); null when absent or non-textual. */
    private static String str(JsonNode metric, String field) {
        JsonNode n = metric.path(field);
        return n.isTextual() ? n.asString() : null;
    }
}
