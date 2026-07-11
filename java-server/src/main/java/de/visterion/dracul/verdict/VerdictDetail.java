package de.visterion.dracul.verdict;

import java.util.List;

public record VerdictDetail(
        String id, String symbol, String companyName,
        List<String> contributingStrigoi, double consensusScore,
        String summary, String createdAt,
        List<String> anomalyTypes, double currentPrice,
        double avgConfidence, String horizon,
        List<String> signals, List<String> risks,
        List<ContributingStrigoiDetail> contributingDetails,
        String decision, String decidedAt,
        String currency, Double nativeCurrentPrice, String nativeCurrency,
        List<String> killCriteriaBreached) {

    /**
     * Back-compat constructor (pre-SP-2 16-arg shape) + native currency. Used by the repository
     * row mapper, which reads the native price + native currency from the DB. Native fields
     * mirror the native value so an un-converted (un-mapped) response is still self-consistent.
     * killCriteriaBreached defaults to empty (callers using this shape predate Task 7).
     */
    public VerdictDetail(
            String id, String symbol, String companyName,
            List<String> contributingStrigoi, double consensusScore,
            String summary, String createdAt,
            List<String> anomalyTypes, double currentPrice,
            double avgConfidence, String horizon,
            List<String> signals, List<String> risks,
            List<ContributingStrigoiDetail> contributingDetails,
            String decision, String decidedAt,
            String currency) {
        this(id, symbol, companyName, contributingStrigoi, consensusScore, summary, createdAt,
                anomalyTypes, currentPrice, avgConfidence, horizon, signals, risks,
                contributingDetails, decision, decidedAt,
                currency, currentPrice, currency, List.of());
    }

    /**
     * Returns a copy with {@code currentPrice} converted to {@code displayCurrency} and the
     * native original preserved in {@code nativeCurrentPrice}/{@code nativeCurrency}.
     */
    public VerdictDetail withConverted(double convertedPrice, String displayCurrency,
                                       double nativePrice, String nativeCurrencyCode) {
        return new VerdictDetail(
                id, symbol, companyName, contributingStrigoi, consensusScore, summary, createdAt,
                anomalyTypes, convertedPrice, avgConfidence, horizon, signals, risks,
                contributingDetails, decision, decidedAt,
                displayCurrency, nativePrice, nativeCurrencyCode, killCriteriaBreached);
    }
}
