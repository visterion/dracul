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
        String decision, String decidedAt) {}
