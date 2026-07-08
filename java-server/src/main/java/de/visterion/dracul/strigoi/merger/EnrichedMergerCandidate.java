package de.visterion.dracul.strigoi.merger;

import java.math.BigDecimal;

/** A merger-arb candidate enriched with the filing's summary term-sheet text and a recent
 *  price — the wire shape returned by the tool webhook. */
public record EnrichedMergerCandidate(
        String symbol,
        String companyName,
        String formType,
        String filingDate,
        String filingUrl,
        String termSheet,
        boolean termSheetAvailable,
        BigDecimal lastPrice,
        boolean priceAvailable
) {}
