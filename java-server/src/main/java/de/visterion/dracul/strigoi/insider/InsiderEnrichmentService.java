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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

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
 *  a source is skipped for all remaining clusters once {@link EnrichmentSourceGuard} declares it
 *  down — either because Agora produced no answer at all
 *  ({@link AgoraUnavailableException.Scope#SOURCE}, or a {@link MarketDataException} of kind
 *  UNAVAILABLE from a non-Agora feed) or because it answered with a per-request error for
 *  {@value EnrichmentSourceGuard#MAX_CONSECUTIVE_REQUEST_FAILURES} clusters in a row. A SINGLE
 *  per-request error (an unknown symbol, an unresolvable issuer) degrades that one cluster and
 *  nothing else — reading it as an outage is what switched the whole enrichment stage off on
 *  2026-08-06. Once two or more sources are down,
 *  enrichment is skipped entirely for the rest of the batch (flags false). Every cluster that
 *  loses a source is counted into {@link EnrichedInsiderBatch#degradedClusters} so the fetch
 *  health can report it as {@code partial}. The coverage
 *  fetch uses {@link AgoraCompanyData#recommendationsStrict} (propagates outages, scope intact)
 *  so its guard is real; the metrics fetch now uses {@link AgoraCompanyData#fundamentalsStrict}
 *  for the same reason — before this task it went through the swallowing {@code fundamentals()},
 *  which meant a TOTAL Agora outage still called {@code recordSuccess()} every cluster, so the
 *  guard read "up" through it while every market cap silently fell to null. {@code
 *  fundamentalsStrict} (not the health-aware {@link AgoraCompanyData#fundamentalsResult}, used
 *  elsewhere by strigoi-lazarus) keeps the {@link AgoraUnavailableException.Scope} on the thrown
 *  exception, so this branch's guard behaves exactly like its four siblings: a SOURCE-scoped
 *  failure trips it immediately, a run of REQUEST-scoped ones (an unresolvable symbol) does not.
 *  The earnings facade still absorbs outages internally (degrading to {@code Optional.empty()})
 *  and is therefore NOT a real canary — a known remaining gap, out of this task's scope. OHLC,
 *  coverage, metrics and owner-history are the exception-based canaries in production. A full
 *  Agora outage is already gated upstream by the Form-4 feed's {@code data_source_health} (no
 *  clusters reach enrichment at all). */
@Component
public class InsiderEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(InsiderEnrichmentService.class);
    private static final int MAX = 25;
    private static final int ADV_LOOKBACK = 20;
    /** Extra calendar days requested beyond Jan 1 so the ADV window survives sparse data. */
    private static final int HISTORY_BUFFER_DAYS = 10;

    private final AgoraMarketData marketData;
    private final AgoraCompanyData companyData;
    private final AgoraEarnings earnings;
    private final AgoraFilings filings;
    private final RoutineClassifier routineClassifier;

    public InsiderEnrichmentService(AgoraMarketData marketData,
                                    AgoraCompanyData companyData,
                                    AgoraEarnings earnings,
                                    AgoraFilings filings,
                                    RoutineClassifier routineClassifier) {
        this.marketData = marketData;
        this.companyData = companyData;
        this.earnings = earnings;
        this.filings = filings;
        this.routineClassifier = routineClassifier;
    }

    /** Per-batch source health: one guard per source, and a source marked down is not queried
     *  again this batch. {@code degradedClusters} counts the clusters that lost at least one
     *  source — the per-item losses that used to leave no trace at all outside a DEBUG line. */
    private static final class SourceHealth {
        final EnrichmentSourceGuard metrics = guard("equity metrics");
        final EnrichmentSourceGuard ohlc = guard("ohlc history");
        final EnrichmentSourceGuard coverage = guard("recommendations");
        final EnrichmentSourceGuard earnings = guard("next-earnings");
        final EnrichmentSourceGuard ownerHistory = guard("form4 owner history");

        int degradedClusters;

        private static EnrichmentSourceGuard guard(String source) {
            return EnrichmentSourceGuard.forSource("insider", "clusters", source);
        }

        int downCount() {
            return (metrics.isDown() ? 1 : 0) + (ohlc.isDown() ? 1 : 0)
                    + (coverage.isDown() ? 1 : 0) + (earnings.isDown() ? 1 : 0)
                    + (ownerHistory.isDown() ? 1 : 0);
        }

        boolean skipAll() { return downCount() >= 2; }
    }

    public EnrichedInsiderBatch enrich(List<InsiderCluster> clusters) {
        List<InsiderCluster> bounded = clusters.stream()
                .sorted(Comparator.comparing(InsiderCluster::totalDollarValue,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .limit(MAX)
                .toList();
        boolean truncated = clusters.size() > MAX;
        if (truncated) {
            log.info("insider enrichment: {} clusters exceed the cap of {}, dropping the {} smallest by totalDollarValue",
                    clusters.size(), MAX, clusters.size() - MAX);
        }
        SourceHealth health = new SourceHealth();
        List<EnrichedInsiderCluster> enriched = bounded.stream().map(c -> enrichOne(c, health)).toList();
        if (health.degradedClusters > 0) {
            log.info("insider enrichment: {} of {} clusters lost at least one enrichment source",
                    health.degradedClusters, enriched.size());
        }
        return new EnrichedInsiderBatch(enriched, truncated, health.degradedClusters);
    }

    private EnrichedInsiderCluster enrichOne(InsiderCluster c, SourceHealth health) {
        if (health.skipAll()) {
            health.degradedClusters++;
            return unenriched(c);
        }
        boolean degraded = false;

        Double marketCap = null;
        if (!health.metrics.isDown()) {
            try {
                // fundamentalsStrict (not fundamentalsResult) so a REQUEST-scoped failure — one
                // unresolvable symbol — reaches recordFailure with its scope intact, exactly like
                // the OHLC/coverage/earnings/owner-history branches below. fundamentalsResult
                // collapses SOURCE and REQUEST into one "unavailable" status, which would trip
                // this guard immediately on a single bad ticker.
                JsonNode m = companyData.fundamentalsStrict(c.ticker());
                if (m != null) marketCap = marketCapitalization(m);
                health.metrics.recordSuccess();
            } catch (RuntimeException e) {
                health.metrics.recordFailure(e);
                degraded = true;
                log.debug("insider enrichment: equity metrics unavailable for {}: {}", c.ticker(), e.getMessage());
            }
        } else {
            degraded = true;
        }

        BigDecimal adv = null;
        BigDecimal ytdReturn = null;
        if (!health.ohlc.isDown()) {
            try {
                List<OhlcBar> bars = marketData.dailyOhlcHistory(c.ticker(), historyDays());
                adv = advFrom(bars);
                ytdReturn = ytdReturnFrom(bars);
                health.ohlc.recordSuccess();
            } catch (RuntimeException e) {
                health.ohlc.recordFailure(e);
                degraded = true;
                log.debug("insider enrichment: ohlc history unavailable for {}: {}", c.ticker(), e.getMessage());
            }
        } else {
            degraded = true;
        }

        Integer coverage = null;
        boolean coverageAvailable = false;
        if (!health.coverage.isDown()) {
            try {
                AnalystCoverage cov = AnalystCoverage.of(companyData.recommendationsStrict(c.ticker()));
                coverage = cov.coverage();
                coverageAvailable = cov.available();
                health.coverage.recordSuccess();
            } catch (RuntimeException e) {
                health.coverage.recordFailure(e);
                degraded = true;
                log.debug("insider enrichment: recommendations unavailable for {}: {}", c.ticker(), e.getMessage());
            }
        } else {
            degraded = true;
        }

        LocalDate nextEarnings = null;
        Integer daysToEarnings = null;
        if (!health.earnings.isDown()) {
            try {
                Optional<LocalDate> next = earnings.nextEarningsDate(c.ticker());
                if (next.isPresent()) {
                    nextEarnings = next.get();
                    daysToEarnings = (int) ChronoUnit.DAYS.between(LocalDate.now(), nextEarnings);
                }
                health.earnings.recordSuccess();
            } catch (RuntimeException e) {
                health.earnings.recordFailure(e);
                degraded = true;
                log.debug("insider enrichment: next-earnings unavailable for {}: {}", c.ticker(), e.getMessage());
            }
        } else {
            degraded = true;
        }

        // Routine/opportunistic classification (Cohen-Malloy-Pomorski). ONE owner-history call
        // per cluster — the tool returns EVERY reporting owner of the company at once, so a
        // cluster with N filers still costs a single Agora call, not N.
        Classification classification = Classification.unavailable(c.filers());
        if (!health.ownerHistory.isDown()) {
            try {
                Form4OwnerHistory history = filings.ownerHistoryStrict(c.ticker());
                classification = classify(c, history);
                health.ownerHistory.recordSuccess();
            } catch (RuntimeException e) {
                health.ownerHistory.recordFailure(e);
                degraded = true;
                log.debug("insider enrichment: owner history unavailable for {}: {}", c.ticker(), e.getMessage());
            }
        } else {
            degraded = true;
        }

        if (degraded) health.degradedClusters++;

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

    /** Same key {@link de.visterion.dracul.strigoi.echo.EquityMetricsExtractor} reads off the raw
     *  fundamentals blob; duplicated here (rather than routed through the extractor) because this
     *  class needs {@link AgoraCompanyData#fundamentalsStrict}'s scope-preserving exception, which
     *  the extractor does not expose. */
    private static Double marketCapitalization(JsonNode metrics) {
        JsonNode n = metrics.path("marketCapitalization");
        return n.isNumber() ? n.asDouble() : null;
    }
}
