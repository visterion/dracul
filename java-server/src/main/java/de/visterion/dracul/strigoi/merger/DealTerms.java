package de.visterion.dracul.strigoi.merger;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Structured deal terms extracted from a merger filing's term-sheet text by
 *  {@link DealTermsParser}. Any component may be null when the term sheet doesn't contain
 *  a recognizable pattern for it.
 *
 *  <p>The three dates power the merger-arb time axis: {@code agreementDate} anchors the
 *  <em>unaffected</em> pre-announcement price (the DEFM14A / SC TO-T feed filings land weeks
 *  or months AFTER the deal was announced, so the price just before the filing is already the
 *  arb price — the agreement/announcement date is the correct anchor), {@code expectedCloseDate}
 *  drives the horizon and the annualized-spread math, and {@code outsideDate} is the deal's
 *  drop-dead / End Date. {@code outsideDate} is intentionally NOT used as a close estimate —
 *  it is the latest permissible close, not the expected one. */
public record DealTerms(
        BigDecimal offerPrice,
        String considerationType, // "cash" | "stock" | "mixed" | null
        String exchangeRatio,
        String breakFee,
        LocalDate agreementDate,
        LocalDate expectedCloseDate,
        LocalDate outsideDate
) {}
