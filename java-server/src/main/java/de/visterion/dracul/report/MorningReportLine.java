package de.visterion.dracul.report;

import java.math.BigDecimal;

/** One held position in the morning report. Numeric fields are null when no
 *  snapshot exists yet (gropar has not run since deploy) — the line is still
 *  rendered so the report never hides a held position. */
public record MorningReportLine(
        String symbol,
        String companyName,
        double shareCount,
        double entryPrice,
        BigDecimal currentClose,
        BigDecimal activeStop,
        BigDecimal nextTarget2r,
        Double distanceToStopPct,   // signed (close-stop)/close*100; null if either missing
        String action,              // SELL | TRIM | HOLD (default HOLD if no signal)
        String thesisStatus,        // INTACT | WEAKENING | INVALIDATED | NONE | null
        Double confidence,
        String rationale,
        OrderTicket ticket
) {}
