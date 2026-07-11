package de.visterion.dracul.strigoi.index;

import java.math.BigDecimal;

/** A screened index-inclusion candidate annotated with liquidity/size metrics so the
 *  LLM can judge forced-buying magnitude against real volume and size instead of guessing.
 *  {@code adv} = average daily dollar volume (close x volume) over the last 20 trading days;
 *  {@code avgVolume20d} = average daily share volume over the same window; {@code marketCap}
 *  comes from {@link de.visterion.dracul.strigoi.echo.EquityMetricsExtractor}. Any of the three
 *  may be null on a per-candidate data-source failure (fail-soft); {@code metricsAvailable} is
 *  false only when ALL three are null. */
public record EnrichedIndexCandidate(
        String symbol,
        String companyName,
        String dateAdded,
        BigDecimal adv,
        Double marketCap,
        Long avgVolume20d,
        boolean metricsAvailable
) {}
