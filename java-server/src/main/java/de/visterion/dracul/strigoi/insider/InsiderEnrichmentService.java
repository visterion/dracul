package de.visterion.dracul.strigoi.insider;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraEarnings;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.Form4OwnerHistory;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.strigoi.EnrichmentSourceGuard;
import de.visterion.dracul.strigoi.echo.AnalystCoverage;
import de.visterion.dracul.strigoi.echo.EquityMetrics;
import de.visterion.dracul.strigoi.echo.EquityMetricsExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Annotates screened insider-buying clusters with deterministic context (size/liquidity,
 *  analyst coverage, calendar-YTD return, next earnings date) plus the routine/opportunistic
 *  classification of each filer (Cohen, Malloy &amp; Pomorski 2012) so the LLM can weigh the
 *  Lakonishok &amp; Lee small/neglected-name effect, the value-trap question, and above all the
 *  opportunistic share against real numbers instead of guessing. Fail-soft: any lookup failure
 *  degrades that one cluster's fields to null (availability flag false), never the run.
 *
 *  <p>The classification calls Agora's {@code get_form4_owner_history} ONCE per cluster (the tool
 *  returns every reporting owner of the company at once, so an N-filer cluster still costs a
 *  single call); the current-purchase context (shares owned following, relative conviction,
 *  10b5-1 plan flag) is derived from that same response — no per-filer call. It obeys the same
 *  {@link EnrichmentSourceGuard} availability guard as the other sources.
 *
 *  <p>Latency guard (the tool webhook has a 30s budget, a dead Agora call burns ~16s):
 *  clusters are sorted by {@code totalDollarValue} descending and bounded to {@link #MAX};
 *  a source that fails with an <em>availability</em> error ({@link AgoraUnavailableException}
 *  or {@link MarketDataException} of kind UNAVAILABLE — as opposed to a symbol-specific
 *  NOT_FOUND) is skipped for all remaining clusters, and once two or more sources are down,
 *  enrichment is skipped entirely for the rest of the batch (flags false). The coverage
 *  fetch uses {@link AgoraCompanyData#recommendationsStrict} (propagates outages) so its
 *  guard is real; the metrics fetch (via the swallowing {@code fundamentals()}) and the
 *  earnings facade still absorb outages internally (degrading to null/empty), so OHLC and
 *  coverage are the exception-based canaries in production. A full Agora outage is already
 *  gated upstream by the Form-4 feed's {@code data_source_health}
 *  (no clusters reach enrichment at all). */
@Component
public class InsiderEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(InsiderEnrichmentService.class);
    private static final int MAX = 25;
    private static final int ADV_LOOKBACK = 20;
    /** Extra calendar days requested beyond Jan 1 so the ADV window survives sparse data. */
    private static final int HISTORY_BUFFER_DAYS = 10;

    private final AgoraMarketData marketData;
    private final EquityMetricsExtractor equityMetrics;
    private final AgoraCompanyData companyData;
    private final AgoraEarnings earnings;
    private final AgoraFilings filings;
    private final RoutineClassifier routineClassifier;

    public InsiderEnrichmentService(AgoraMarketData marketData,
                                    EquityMetricsExtractor equityMetrics,
                                    AgoraCompanyData companyData,
                                    AgoraEarnings earnings,
                                    AgoraFilings filings,
                                    RoutineClassifier routineClassifier) {
        this.marketData = marketData;
        this.equityMetrics = equityMetrics;
        this.companyData = companyData;
        this.earnings = earnings;
        this.filings = filings;
        this.routineClassifier = routineClassifier;
    }

    /** Per-batch source health: a source marked down is not queried again this batch. */
    private static final class SourceHealth {
        boolean metricsDown, ohlcDown, coverageDown, earningsDown, ownerHistoryDown;

        int downCount() {
            return (metricsDown ? 1 : 0) + (ohlcDown ? 1 : 0)
                    + (coverageDown ? 1 : 0) + (earningsDown ? 1 : 0)
                    + (ownerHistoryDown ? 1 : 0);
        }

        boolean skipAll() { return downCount() >= 2; }
    }

    public List<EnrichedInsiderCluster> enrich(List<InsiderCluster> clusters) {
        List<InsiderCluster> bounded = clusters.stream()
                .sorted(Comparator.comparing(InsiderCluster::totalDollarValue,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .limit(MAX)
                .toList();
        if (clusters.size() > MAX) {
            log.info("insider enrichment: {} clusters exceed the cap of {}, dropping the {} smallest by totalDollarValue",
                    clusters.size(), MAX, clusters.size() - MAX);
        }
        SourceHealth health = new SourceHealth();
        return bounded.stream().map(c -> enrichOne(c, health)).toList();
    }

    private EnrichedInsiderCluster enrichOne(InsiderCluster c, SourceHealth health) {
        if (health.skipAll()) {
            return unenriched(c);
        }

        Double marketCap = null;
        if (!health.metricsDown) {
            try {
                EquityMetrics em = equityMetrics.metrics(c.ticker());
                if (em.available()) marketCap = em.marketCap();
            } catch (RuntimeException e) {
                health.metricsDown = EnrichmentSourceGuard.isSourceDown(e, "insider", "clusters", "equity metrics");
                log.debug("insider enrichment: equity metrics unavailable for {}: {}", c.ticker(), e.getMessage());
            }
        }

        BigDecimal adv = null;
        BigDecimal ytdReturn = null;
        if (!health.ohlcDown) {
            try {
                List<OhlcBar> bars = marketData.dailyOhlcHistory(c.ticker(), historyDays());
                adv = advFrom(bars);
                ytdReturn = ytdReturnFrom(bars);
            } catch (RuntimeException e) {
                health.ohlcDown = EnrichmentSourceGuard.isSourceDown(e, "insider", "clusters", "ohlc history");
                log.debug("insider enrichment: ohlc history unavailable for {}: {}", c.ticker(), e.getMessage());
            }
        }

        Integer coverage = null;
        boolean coverageAvailable = false;
        if (!health.coverageDown) {
            try {
                AnalystCoverage cov = AnalystCoverage.of(companyData.recommendationsStrict(c.ticker()));
                coverage = cov.coverage();
                coverageAvailable = cov.available();
            } catch (RuntimeException e) {
                health.coverageDown = EnrichmentSourceGuard.isSourceDown(e, "insider", "clusters", "recommendations");
                log.debug("insider enrichment: recommendations unavailable for {}: {}", c.ticker(), e.getMessage());
            }
        }

        LocalDate nextEarnings = null;
        Integer daysToEarnings = null;
        if (!health.earningsDown) {
            try {
                Optional<LocalDate> next = earnings.nextEarningsDate(c.ticker());
                if (next.isPresent()) {
                    nextEarnings = next.get();
                    daysToEarnings = (int) ChronoUnit.DAYS.between(LocalDate.now(), nextEarnings);
                }
            } catch (RuntimeException e) {
                health.earningsDown = EnrichmentSourceGuard.isSourceDown(e, "insider", "clusters", "next-earnings");
                log.debug("insider enrichment: next-earnings unavailable for {}: {}", c.ticker(), e.getMessage());
            }
        }

        // Routine/opportunistic classification (Cohen-Malloy-Pomorski). ONE owner-history call
        // per cluster — the tool returns EVERY reporting owner of the company at once, so a
        // cluster with N filers still costs a single Agora call, not N.
        Classification classification = Classification.unavailable(c.filers());
        if (!health.ownerHistoryDown) {
            try {
                Form4OwnerHistory history = filings.ownerHistoryStrict(c.ticker());
                classification = classify(c, history);
            } catch (RuntimeException e) {
                health.ownerHistoryDown = EnrichmentSourceGuard.isSourceDown(e, "insider", "clusters", "form4 owner history");
                log.debug("insider enrichment: owner history unavailable for {}: {}", c.ticker(), e.getMessage());
            }
        }

        if (health.skipAll()) {
            log.info("insider enrichment: {} sources down, skipping enrichment for the remaining clusters",
                    health.downCount());
        }

        return new EnrichedInsiderCluster(
                c.ticker(), c.companyName(), classification.filers(), c.windowStart(), c.windowEnd(),
                c.totalDollarValue(), c.totalShares(), c.concurrentInsiderSells(), c.netInsiderDollar(),
                marketCap, adv, marketCap != null || adv != null,
                coverage, coverageAvailable,
                ytdReturn, ytdReturn != null,
                nextEarnings, daysToEarnings, nextEarnings != null,
                classification.opportunisticShare(), classification.classifiedFilers(),
                classification.unknownFilers(), classification.available());
    }

    private static EnrichedInsiderCluster unenriched(InsiderCluster c) {
        Classification classification = Classification.unavailable(c.filers());
        return new EnrichedInsiderCluster(
                c.ticker(), c.companyName(), classification.filers(), c.windowStart(), c.windowEnd(),
                c.totalDollarValue(), c.totalShares(), c.concurrentInsiderSells(), c.netInsiderDollar(),
                null, null, false, null, false, null, false, null, null, false,
                classification.opportunisticShare(), classification.classifiedFilers(),
                classification.unknownFilers(), classification.available());
    }

    /** Aggregate routine/opportunistic outcome for one cluster: the reclassified filers plus the
     *  cluster-level rollup ({@code opportunisticShare} over the classifiable filers). */
    private record Classification(List<InsiderFiler> filers, BigDecimal opportunisticShare,
                                  int classifiedFilers, int unknownFilers, boolean available) {
        /** Owner history down/skipped: every filer stays UNKNOWN, share null, not available. */
        static Classification unavailable(List<InsiderFiler> filers) {
            return new Classification(filers, null, 0, filers.size(), false);
        }
    }

    /** Classify every filer of a cluster against the company's owner history and roll up the
     *  cluster-level opportunistic share. */
    private Classification classify(InsiderCluster c, Form4OwnerHistory history) {
        List<InsiderFiler> classified = c.filers().stream()
                .map(f -> classifyFiler(c, f, history))
                .toList();
        int routine = 0, opportunistic = 0, unknown = 0;
        for (InsiderFiler f : classified) {
            switch (f.classification()) {
                case ROUTINE -> routine++;
                case OPPORTUNISTIC -> opportunistic++;
                case UNKNOWN -> unknown++;
            }
        }
        int classifiable = routine + opportunistic;
        BigDecimal share = classifiable == 0 ? null
                : BigDecimal.valueOf(opportunistic).divide(BigDecimal.valueOf(classifiable), 4, RoundingMode.HALF_UP);
        return new Classification(classified, share, classifiable, unknown, true);
    }

    /** Match one cluster filer to its owner in the history (by name), then classify and attach the
     *  current-purchase context (shares owned following, relative conviction, 10b5-1 plan flag).
     *  All context is derived from the SAME owner history — no extra Agora call. */
    private InsiderFiler classifyFiler(InsiderCluster c, InsiderFiler filer, Form4OwnerHistory history) {
        Form4OwnerHistory.Owner owner = matchOwner(history, filer.name());
        if (owner == null) {
            return filer.withClassification(FilerClassification.UNKNOWN, null, null, null);
        }
        List<Form4OwnerHistory.Transaction> windowBuys = owner.transactions().stream()
                .filter(t -> t.transactionDate() != null && "P".equalsIgnoreCase(t.code()))
                .filter(t -> !t.transactionDate().isBefore(c.windowStart())
                        && !t.transactionDate().isAfter(c.windowEnd()))
                .sorted(Comparator.comparing(Form4OwnerHistory.Transaction::transactionDate))
                .toList();
        Form4OwnerHistory.Transaction mostRecent =
                windowBuys.isEmpty() ? null : windowBuys.get(windowBuys.size() - 1);
        LocalDate reference = mostRecent != null ? mostRecent.transactionDate() : c.windowEnd();

        FilerClassification cls = routineClassifier.classify(owner.transactions(), reference, history.truncated());
        BigDecimal sharesOwnedFollowing = mostRecent != null ? mostRecent.sharesOwnedFollowing() : null;
        BigDecimal pctOfHoldings = purchaseAsPctOfHoldings(windowBuys, sharesOwnedFollowing);
        Boolean planned = planned10b5(windowBuys);
        return filer.withClassification(cls, sharesOwnedFollowing, pctOfHoldings, planned);
    }

    /** Match a cluster filer to its owner in the history by exact, case-insensitive name.
     *
     *  <p>KNOWN MATCH-RATE LIMIT: the owner history carries each owner's stable CIK, but the
     *  filer side does NOT — the screener builds {@link InsiderFiler} from {@code Form4Filing},
     *  a record shared with daywalker that does not carry {@code filerCik}. Threading the CIK
     *  through would force edits to the shared record and its daywalker construction sites
     *  (out of scope for this slice), so matching stays name-based. Names come from the same
     *  EDGAR parser on both sides, so within one company solo filings align; the residual misses
     *  are name variants (suffixes, joint-filing name joins, ordering). An unmatched filer stays
     *  UNKNOWN — conservative (never counted as opportunistic), so a miss only forgoes a signal,
     *  it never fabricates one. */
    private static Form4OwnerHistory.Owner matchOwner(Form4OwnerHistory history, String filerName) {
        String norm = normalize(filerName);
        if (norm.isEmpty()) return null;
        for (Form4OwnerHistory.Owner o : history.owners()) {
            if (normalize(o.name()).equals(norm)) return o;
        }
        return null;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    /** Cluster-window purchase shares ÷ post-transaction holdings (relative conviction); null when
     *  holdings are unknown/non-positive. */
    private static BigDecimal purchaseAsPctOfHoldings(List<Form4OwnerHistory.Transaction> windowBuys,
                                                      BigDecimal sharesOwnedFollowing) {
        if (sharesOwnedFollowing == null || sharesOwnedFollowing.signum() <= 0) return null;
        BigDecimal bought = windowBuys.stream()
                .map(Form4OwnerHistory.Transaction::shares)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (bought.signum() <= 0) return null;
        return bought.divide(sharesOwnedFollowing, 4, RoundingMode.HALF_UP);
    }

    /** Tri-state 10b5-1(c) rollup over the filer's cluster-window purchases: TRUE if any is a plan
     *  trade, FALSE if all carry an explicit false, null when none carries the (2023+) flag. */
    private static Boolean planned10b5(List<Form4OwnerHistory.Transaction> windowBuys) {
        boolean anyFalse = false;
        for (Form4OwnerHistory.Transaction t : windowBuys) {
            Boolean flag = t.aff10b5One();
            if (Boolean.TRUE.equals(flag)) return Boolean.TRUE;
            if (Boolean.FALSE.equals(flag)) anyFalse = true;
        }
        return anyFalse ? Boolean.FALSE : null;
    }

    /** Enough history to cover Jan 1 of the current year (YTD) and the 20-day ADV window. */
    private static int historyDays() {
        LocalDate today = LocalDate.now();
        long sinceJan1 = ChronoUnit.DAYS.between(LocalDate.of(today.getYear(), 1, 1), today);
        return (int) Math.max(ADV_LOOKBACK + HISTORY_BUFFER_DAYS, sinceJan1 + HISTORY_BUFFER_DAYS);
    }

    /** Average daily dollar volume (close x volume) over the last {@link #ADV_LOOKBACK} bars. */
    private static BigDecimal advFrom(List<OhlcBar> bars) {
        if (bars.size() < ADV_LOOKBACK) return null;
        List<OhlcBar> recent = bars.subList(bars.size() - ADV_LOOKBACK, bars.size());
        BigDecimal dollarSum = BigDecimal.ZERO;
        for (OhlcBar b : recent) {
            dollarSum = dollarSum.add(b.close().multiply(BigDecimal.valueOf(b.volume())));
        }
        return dollarSum.divide(BigDecimal.valueOf(ADV_LOOKBACK), 0, RoundingMode.HALF_UP);
    }

    /** (last close − first close of the calendar year) / first close, as a decimal fraction. */
    private static BigDecimal ytdReturnFrom(List<OhlcBar> bars) {
        int year = LocalDate.now().getYear();
        List<OhlcBar> thisYear = bars.stream().filter(b -> b.date().getYear() == year).toList();
        if (thisYear.size() < 2) return null;
        BigDecimal first = thisYear.get(0).close();
        BigDecimal last = thisYear.get(thisYear.size() - 1).close();
        if (first == null || last == null || first.signum() <= 0) return null;
        return last.subtract(first).divide(first, 4, RoundingMode.HALF_UP);
    }
}
