package de.visterion.dracul.strigoi.spin;

/** Structured distribution terms extracted from a spin-off information statement's term-sheet
 *  text by {@link SpinTermsParser}. Any component may be null when the term sheet doesn't
 *  contain a recognizable pattern for it. */
public record SpinTerms(
        String distributionRatio,
        String recordDate,
        String distributionDate
) {}
