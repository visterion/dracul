package de.visterion.dracul.strigoi.lazarus;

import java.math.BigDecimal;

/** A {@link LazarusCandidate} enriched with the Piotroski F-Score + accruals read from Agora
 *  filings — the wire shape returned by the tool webhook after {@link LazarusEnrichmentService}. */
public record EnrichedLazarusCandidate(
        String symbol,
        String companyName,
        double currentPrice,
        double week52Low,
        double week52High,
        double pctAboveLow,
        Double roaTtm,
        Double currentRatio,
        Double debtToEquity,
        Double grossMargin,
        Double netMargin,
        Double revenueGrowthYoy,
        Double epsGrowthYoy,
        Double priceToBook,
        Double peTtm,
        Double fcfPerShare,
        int fScore,
        int fScoreCriteriaAvailable,
        BigDecimal accrualRatio,
        boolean cfoExceedsNetIncome
) {}
