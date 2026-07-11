package de.visterion.dracul.strigoi.spin;

/** A spin-off candidate enriched with the filing's summary term-sheet text — the wire shape
 *  returned by the tool webhook. No price: the spin thesis is forced-selling / size. */
public record EnrichedSpinCandidate(
        String symbol,
        String companyName,
        String formType,
        String filingDate,
        String filingUrl,
        String termSheet,
        boolean termSheetAvailable,
        String distributionRatio,
        String recordDate,
        String distributionDate
) {}
