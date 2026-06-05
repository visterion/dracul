package de.visterion.dracul.strigoi.merger;

/** A screened merger-arb candidate — the wire shape returned by the tool webhook. */
public record MergerCandidate(
        String symbol,        // may be empty
        String companyName,
        String formType,
        String filingDate,    // ISO date string
        String filingUrl
) {}
