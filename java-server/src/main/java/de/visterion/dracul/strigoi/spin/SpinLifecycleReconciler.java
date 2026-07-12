package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.ConceptSeries;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, cheap spin-off lifecycle reconciliation. Modelled on
 * {@link de.visterion.dracul.gropar.GroparPauseReconciler} — recompute the desired state from the
 * persisted rows, then apply the delta via guarded compare-and-set — but here the "desired state"
 * is a set of forward-only status transitions rather than a single boolean.
 *
 * <p>{@link #reconcile()} runs two phases, both fail-soft, in order:
 * <ol>
 *   <li><b>Calendar transitions</b> (pure SQL/Java, zero Agora calls): REGISTERED &rarr; WHEN_ISSUED
 *       when {@code record_date} has arrived and distribution has not; REGISTERED/WHEN_ISSUED &rarr;
 *       ABANDONED once a row has sat non-distributed past {@code abandon-after-days}.</li>
 *   <li><b>Quote probe</b> (ONE batched {@link AgoraMarketData#quotes} call across every
 *       symbol-bearing REGISTERED/WHEN_ISSUED row): a positive price means the spin-co is trading,
 *       so REGISTERED/WHEN_ISSUED &rarr; DISTRIBUTED. When {@code distribution_date} is known and
 *       past this is a formality; when it is unknown the quote resolution IS the primary
 *       distribution signal (blueprint §2). {@code quotes()} is internally swallowing — an Agora
 *       outage yields an empty map (no throw), so a down source simply produces zero transitions
 *       this run and is retried next run; there is no per-batch source-down flag to keep because it
 *       is a single call.</li>
 * </ol>
 *
 * <p>The DISTRIBUTED &rarr; SETTLED transition is NOT here: it needs an XBRL {@code filed} date and
 * is folded into the enrichment phase (see {@link #detectSettled} and {@link SpinCandidateEnricher}).
 * All UPDATEs are the repository's guarded CAS, so a duplicate/concurrent reconcile is a no-op and
 * transitions never reverse.
 */
@Component
public class SpinLifecycleReconciler {

    private static final Logger log = LoggerFactory.getLogger(SpinLifecycleReconciler.class);

    /** Spin-offs are rare; a generous scan bound covers every tracked non-terminal row cheaply. */
    private static final int SCAN_LIMIT = 1000;

    private final SpinCandidateRepository repo;
    private final AgoraMarketData marketData;
    private final int abandonAfterDays;

    public SpinLifecycleReconciler(
            SpinCandidateRepository repo,
            AgoraMarketData marketData,
            @Value("${dracul.strigoi.spin.abandon-after-days:180}") int abandonAfterDays) {
        this.repo = repo;
        this.marketData = marketData;
        this.abandonAfterDays = abandonAfterDays;
    }

    /** Outcome of one reconcile pass: the ids that moved (WHEN_ISSUED/DISTRIBUTED) so the
     *  enrichment phase can prioritise freshly-transitioned rows over routine re-checks. */
    public record ReconcileResult(Set<Long> transitionedIds) {
        public static ReconcileResult empty() { return new ReconcileResult(Set.of()); }
    }

    public ReconcileResult reconcile() {
        return reconcile(LocalDate.now());
    }

    /** Package-private date seam for deterministic tests. */
    ReconcileResult reconcile(LocalDate today) {
        List<SpinCandidateRow> rows = repo.findNonTerminalOldestCheckedFirst(SCAN_LIMIT);
        Set<Long> transitioned = new LinkedHashSet<>();

        // Phase 1 — calendar transitions. Track each row's post-transition status locally so the
        // quote probe below sees the up-to-date "from" state without a re-query.
        Map<Long, SpinStatus> current = new HashMap<>();
        for (SpinCandidateRow row : rows) {
            SpinStatus status;
            try {
                status = applyCalendar(row, today, transitioned);
            } catch (RuntimeException e) {
                // Per-row fail-soft (parity with the enrich phase): a DB/parse failure on one row
                // degrades it to "unchanged this run", never kills the whole reconcile + hunt.
                log.debug("spin reconcile: calendar transition failed for row {}: {}", row.id(), e.getMessage());
                status = row.status();
            }
            current.put(row.id(), status);
        }

        // Phase 2 — batched quote probe over the still-pre-distribution, symbol-bearing rows.
        applyQuoteProbe(rows, current, transitioned);

        if (!transitioned.isEmpty()) {
            log.info("spin reconcile: {} rows transitioned", transitioned.size());
        }
        return new ReconcileResult(transitioned);
    }

    /** Applies the calendar transition for one row and returns its resulting status. */
    private SpinStatus applyCalendar(SpinCandidateRow row, LocalDate today, Set<Long> transitioned) {
        SpinStatus status = row.status();
        if (status != SpinStatus.REGISTERED && status != SpinStatus.WHEN_ISSUED) {
            return status; // DISTRIBUTED (or anything else) is not calendar-driven here
        }

        // ABANDONED: sat non-distributed past the abandon window. discovered_at is an ISO
        // timestamp; a missing/unparsable value degrades to "not old enough" (never abandon blind).
        LocalDate discovered = dateOf(row.discoveredAt());
        if (discovered != null && discovered.plusDays(abandonAfterDays).isBefore(today)) {
            if (repo.advanceStatus(row.id(), status, SpinStatus.ABANDONED)) {
                transitioned.add(row.id());
                return SpinStatus.ABANDONED;
            }
        }

        // WHEN_ISSUED: record date reached, distribution not yet. Only from REGISTERED.
        if (status == SpinStatus.REGISTERED
                && row.recordDate() != null
                && !today.isBefore(row.recordDate())
                && (row.distributionDate() == null || today.isBefore(row.distributionDate()))) {
            if (repo.advanceStatus(row.id(), SpinStatus.REGISTERED, SpinStatus.WHEN_ISSUED)) {
                transitioned.add(row.id());
                return SpinStatus.WHEN_ISSUED;
            }
        }
        return status;
    }

    /** ONE batched quote lookup; each symbol that resolves to a positive price moves its row to
     *  DISTRIBUTED (guarded on its current local status). Empty quotes (down source or no data)
     *  leave every row untouched. */
    private void applyQuoteProbe(List<SpinCandidateRow> rows, Map<Long, SpinStatus> current,
                                 Set<Long> transitioned) {
        // symbol -> rows still pre-distribution carrying that symbol
        Map<String, List<SpinCandidateRow>> bySymbol = new HashMap<>();
        for (SpinCandidateRow row : rows) {
            SpinStatus status = current.get(row.id());
            if (status != SpinStatus.REGISTERED && status != SpinStatus.WHEN_ISSUED) continue;
            String symbol = row.symbol();
            if (symbol == null || symbol.isBlank()) continue;
            bySymbol.computeIfAbsent(symbol, k -> new ArrayList<>()).add(row);
        }
        if (bySymbol.isEmpty()) return;

        Map<String, Quote> quotes = marketData.quotes(bySymbol.keySet()); // empty on outage, never throws
        for (Map.Entry<String, Quote> e : quotes.entrySet()) {
            Quote q = e.getValue();
            if (q == null || q.price() == null || q.price().signum() <= 0) continue; // not trading yet
            for (SpinCandidateRow row : bySymbol.getOrDefault(e.getKey(), List.of())) {
                SpinStatus from = current.get(row.id());
                if (repo.advanceStatus(row.id(), from, SpinStatus.DISTRIBUTED)) {
                    current.put(row.id(), SpinStatus.DISTRIBUTED);
                    transitioned.add(row.id());
                }
            }
        }
    }

    /**
     * PURE settlement predicate over an already-fetched XBRL Assets series (zero Agora calls of its
     * own, zero DB writes — the caller supplies the series). A spin-co has "settled" once it files
     * its first standalone report: a datapoint whose {@code periodEnd} AND {@code filed} both fall
     * strictly after the distribution date. The distribution date is the term-sheet
     * {@code distribution_date} when known, else the {@code distributed_at} detection date (the
     * quote-probe stamp) as a fallback so a term-sheet-less spin-co can still settle.
     *
     * <p>Deliberately does NOT advance the status: SETTLED is terminal, so the caller must first
     * secure the SETTLED valuation snapshot and only then call {@link #advanceToSettled} — otherwise
     * a transient valuation-fetch failure would burn the transition with an empty, never-revisited
     * snapshot. Fail-soft: a null/absent distribution date or an empty series returns false.
     */
    public boolean isSettled(SpinCandidateRow row, ConceptSeries assetsSeries, LocalDate today) {
        if (row.status() != SpinStatus.DISTRIBUTED) return false;
        LocalDate distribution = effectiveDistributionDate(row);
        if (distribution == null || assetsSeries == null || assetsSeries.isEmpty()) return false;
        return assetsSeries.points().stream().anyMatch(p ->
                p.periodEnd() != null && p.filed() != null
                        && p.periodEnd().isAfter(distribution) && p.filed().isAfter(distribution));
    }

    /** Commits the DISTRIBUTED &rarr; SETTLED transition via the repository's guarded CAS (a
     *  stale/duplicate call is a no-op). Call ONLY after the valuation snapshot is in hand (see
     *  {@link #isSettled}). Returns whether the row moved this call. */
    public boolean advanceToSettled(long id) {
        return repo.advanceStatus(id, SpinStatus.DISTRIBUTED, SpinStatus.SETTLED);
    }

    /** The best-available distribution date: the parsed term-sheet date, else the detection
     *  (quote-probe) timestamp's date. Null when neither is known. */
    static LocalDate effectiveDistributionDate(SpinCandidateRow row) {
        if (row.distributionDate() != null) return row.distributionDate();
        return dateOf(row.distributedAt());
    }

    /** ISO timestamp/date string -> LocalDate; null on null/blank/unparsable (fail-soft). */
    private static LocalDate dateOf(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isBlank()) return null;
        try {
            return LocalDate.parse(isoTimestamp.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
