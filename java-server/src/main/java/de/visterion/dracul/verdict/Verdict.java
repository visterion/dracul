package de.visterion.dracul.verdict;

import java.util.List;

public record Verdict(
        String id, String symbol, String companyName,
        List<String> contributingStrigoi, double consensusScore,
        String summary, String createdAt) {}
