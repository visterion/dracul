package de.visterion.dracul.strigoi.spin;

/** A screened spin-off candidate — the wire shape returned by the tool webhook. */
public record SpinCandidate(
        String symbol,        // may be empty
        String companyName,
        String formType,
        String filingDate,    // ISO date string
        String filingUrl
) {}
