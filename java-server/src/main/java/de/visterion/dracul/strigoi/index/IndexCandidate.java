package de.visterion.dracul.strigoi.index;

/** A screened index-inclusion candidate — the wire shape returned by the tool webhook. */
public record IndexCandidate(
        String symbol,
        String companyName,
        String dateAdded    // ISO date string
) {}
