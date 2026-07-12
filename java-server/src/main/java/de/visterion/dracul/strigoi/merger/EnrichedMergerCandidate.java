package de.visterion.dracul.strigoi.merger;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A merger-arb candidate enriched with the filing's summary term-sheet text and a recent
 *  price — the wire shape returned by the tool webhook.
 *
 *  <p>The trailing block adds the Mitchell &amp; Pulvino (2001) expected-value inputs: the deal
 *  time-axis dates parsed from the term sheet ({@code agreementDate}, {@code expectedCloseDate},
 *  {@code outsideDate}), the pre-announcement {@code unaffectedPrice} anchored on the last
 *  trading day before {@code agreementDate}, and three server-computed derivations. Each may be
 *  null independently.
 *
 *  <p>{@code unaffectedPriceAvailable} carries mixed-cause semantics: it is {@code false} both
 *  when no {@code agreementDate} could be parsed (nothing to anchor on — symbol-specific) AND
 *  when the OHLC lookup failed or the agreement predates the bounded lookback window (the
 *  unaffected close is simply out of reach). The other numeric fields are absent (null) whenever
 *  any of their inputs are missing, never zero-filled. */
public record EnrichedMergerCandidate(
        String symbol,
        String companyName,
        String formType,
        String filingDate,
        String filingUrl,
        String termSheet,
        boolean termSheetAvailable,
        BigDecimal lastPrice,
        boolean priceAvailable,
        BigDecimal offerPrice,
        String considerationType,
        String exchangeRatio,
        String breakFee,
        BigDecimal spreadPercent,
        LocalDate agreementDate,
        LocalDate expectedCloseDate,
        LocalDate outsideDate,
        BigDecimal unaffectedPrice,
        boolean unaffectedPriceAvailable,
        Integer daysToClose,
        BigDecimal annualizedSpreadPercent,
        BigDecimal breakDownsidePercent
) {}
