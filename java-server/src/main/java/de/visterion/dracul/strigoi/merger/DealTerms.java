package de.visterion.dracul.strigoi.merger;

import java.math.BigDecimal;

/** Structured deal terms extracted from a merger filing's term-sheet text by
 *  {@link DealTermsParser}. Any component may be null when the term sheet doesn't contain
 *  a recognizable pattern for it. */
public record DealTerms(
        BigDecimal offerPrice,
        String considerationType, // "cash" | "stock" | "mixed" | null
        String exchangeRatio,
        String breakFee
) {}
