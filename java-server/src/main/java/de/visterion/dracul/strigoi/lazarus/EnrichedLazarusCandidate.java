package de.visterion.dracul.strigoi.lazarus;

import java.math.BigDecimal;

/** A {@link LazarusCandidate} enriched with the Piotroski F-Score + accruals read from Agora
 *  filings — the wire shape returned by the tool webhook after {@link LazarusEnrichmentService}.
 *  {@code cfoExceedsNetIncome} is only meaningful when {@code cfoExceedsNetIncomeAvailable} is
 *  true: candidates whose signal was available AND false are hard-dropped by the enrichment
 *  service, so a wire-level {@code false} always means the check was not computable — never a
 *  quality warning.
 *
 *  <p>The trailing timing/stabilization signals are deterministic, computed from one Agora
 *  daily-OHLC series (~260 trading days) and fail-soft: {@code priceVs50dMa} = last close /
 *  50-day close average − 1 as a decimal fraction (null with fewer than 50 bars);
 *  {@code weeksSinceNewLow} = full weeks since the trading day that set the lowest close of
 *  the last ~252 bars, 0 meaning the low is less than a week old (null with fewer than ~252
 *  bars); {@code momentum3m} = last close / close ~63 bars earlier − 1 as a decimal fraction
 *  (null with fewer than 63 bars). {@code timingAvailable} is false only when ALL three
 *  signals are null (OHLC data unavailable, or the price history is too short for even the
 *  smallest window — e.g. a recent IPO) — a true flag does NOT guarantee every
 *  field (e.g. {@code priceVs50dMa}/{@code momentum3m} present while {@code weeksSinceNewLow}
 *  is null on a short series); a null field always means unknown.
 *
 *  <p>{@code zScore} is the classic Altman Z-Score (1968) computed server-side by
 *  {@link AltmanZCalculator} from SEC XBRL concepts plus the Finnhub market cap
 *  (scale 2). There is no partial Z: {@code zScoreAvailable} is false — and {@code zScore}
 *  null — whenever ANY of the five ratio inputs is missing, the balance-sheet/fiscal-year
 *  dates do not line up, or the concept source was down.
 *
 *  <p>The trailing analyst-revision fields reuse the echo SP3 mechanics on ONE
 *  recommendation-trend response per candidate: {@code netEstimateRevisionsProxy} =
 *  latest-period net minus previous-period net (net = strongBuy + buy − sell − strongSell,
 *  {@link de.visterion.dracul.strigoi.echo.RevisionsProxy}); {@code netEstimateRevisionsDirection}
 *  = {@code "up"|"down"|"flat"}, the sign of that proxy; {@code analystCoverage} = analyst
 *  count of the latest period ({@link de.visterion.dracul.strigoi.echo.AnalystCoverage}).
 *  One shared {@code revisionsAvailable} flag covers all three — echo carries two flags
 *  ({@code revisionsAvailable}/{@code coverageAvailable}) only because the values live in
 *  two records there; both are false exactly when the shared trend response is empty, so
 *  they can never diverge. When {@code revisionsAvailable} is false all three fields are
 *  null (no trend at all — unknown, never a judgement). */
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
        boolean cfoExceedsNetIncome,
        boolean cfoExceedsNetIncomeAvailable,
        BigDecimal priceVs50dMa,
        Integer weeksSinceNewLow,
        BigDecimal momentum3m,
        boolean timingAvailable,
        BigDecimal zScore,
        boolean zScoreAvailable,
        Integer netEstimateRevisionsProxy,
        String netEstimateRevisionsDirection,
        Integer analystCoverage,
        boolean revisionsAvailable
) {}
