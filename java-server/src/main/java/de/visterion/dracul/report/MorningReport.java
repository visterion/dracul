package de.visterion.dracul.report;

import java.util.List;

/** The day's report for one owner. A projection, not a stored entity. */
public record MorningReport(
        String generatedAt,            // ISO instant, stamped at build time
        int sellCount, int trimCount, int holdCount,
        List<MorningReportLine> positions
) {}
