package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import de.visterion.dracul.hunting.agora.FilingText;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ENRICH + RESPOND phases of the spin hunt (see {@link StrigoiSpinWebhookController}).
 *
 * <p><b>ENRICH</b> ({@link #enrich}) fetches stage-appropriate data for a bounded set of rows and
 * persists it as per-stage JSONB snapshots. The set is (rows that reached a new stage this run) ∪
 * (non-terminal rows due for a re-check), freshly-transitioned first then oldest-checked, capped at
 * {@link #MAX} to hold the webhook latency budget.
 *
 * <p><b>Term capture</b> ({@link #captureTerms}) runs FIRST for EVERY row in the queue — REGISTERED,
 * WHEN_ISSUED and DISTRIBUTED alike, regardless of strict-source health — before the per-status
 * switch below. The strict-source guard ({@code health.skipAll()}) only skips that per-status
 * switch for the rest of the batch; it must never stop the loop itself, or an XBRL/EDGAR outage
 * would silently cost term capture for every queued row behind the one that tripped it — the same
 * source/term-sheet coupling this task exists to undo. Five production rows (DISTRIBUTED with a
 * stale term sheet from the Form-10 shell) were never captured at all under the old
 * REGISTERED-only precondition; this is the fix. See {@link #captureTerms} for the due-check, the
 * fallback-vs-good-text guard and the throttle.
 *
 * <p>Then, per row, by current status:
 * <ul>
 *   <li><b>REGISTERED / WHEN_ISSUED</b> — the pre-distribution balance sheet via
 *       {@link SpinBalanceSheetSnapshotter} &rarr; {@code registered_snapshot}.</li>
 *   <li><b>DISTRIBUTED</b> — a settlement probe (ONE {@code conceptStrict(cik,"Assets")} call, fed to
 *       {@link SpinLifecycleReconciler#detectSettled}); if it settles, the SETTLED valuation via
 *       {@link SpinValuationSnapshotter} &rarr; {@code settled_snapshot}; otherwise the distribution
 *       snapshot via {@link SpinDistributionSnapshotter} &rarr; {@code distributed_snapshot}.</li>
 * </ul>
 *
 * <p><b>Settlement / concept-fetch coupling.</b> The blueprint's "settlement piggybacks the concept
 * fetch, 0 extra calls" cannot hold literally: the DISTRIBUTED-stage snapshotter fetches Finnhub
 * market caps + Form-4 owner history, NOT XBRL, so there is no in-phase concept fetch to ride. A
 * DISTRIBUTED row therefore issues ONE dedicated {@code conceptStrict Assets} probe here; when it
 * fires the transition, the following valuation snapshot re-fetches Assets (one redundant call,
 * accepted — spin-offs are rare and this is bounded by {@link #MAX}). Documented coupling, fully
 * fail-soft.
 *
 * <p><b>Parent symbol.</b> Best-effort: at REGISTERED-stage term capture the parent ticker is
 * extracted from an exchange-qualified parenthetical in the term-sheet prose (e.g.
 * "Parent Corp (NYSE: XYZ)") via {@link SpinTermsParser#parentTicker}, discarding a match that
 * equals the spin-co's own symbol, and persisted as {@code parent_symbol}. When resolvable it is
 * passed to the distribution snapshotter so {@code sizeRatio} becomes computable; when the term
 * sheet names only a parent (no exchange:ticker) it stays null and the parent/size fields degrade
 * to null (fail-soft — no unreliable name&rarr;ticker heuristic). The LLM still sees the parent in
 * the persisted {@code termSheet} prose. Each candidate resolves its own parent independently
 * (spin-offs are rare), so there is no cross-candidate parent-fetch to de-duplicate.
 *
 * <p><b>Source-down guard.</b> Mirrors the lazarus/insider enrichers: the two strict sources —
 * XBRL concepts (balance sheet / valuation / settlement probe) and Form-4 owner history
 * (distribution) — each propagate {@link AgoraUnavailableException}; the first such failure marks
 * that source down for the rest of the batch (skips it, degrades those fields to null), and once
 * BOTH are down enrichment is skipped entirely for the remaining rows (the insider {@code >= 2}
 * threshold, here the whole strict-source set). Finnhub market caps / fundamentals / the term-sheet
 * text all go through swallowing facades and can never trip the guard.
 *
 * <p><b>RESPOND</b> ({@link #payload(LocalDate)}) rebuilds {@link EnrichedSpinCandidate} rows from the
 * persisted columns + snapshots for the active, unpromoted statuses {REGISTERED, WHEN_ISSUED,
 * DISTRIBUTED}, restricted to the hunt's requested date window. This replaces the old "read straight
 * from the live search" path — and, since the window predicate was added, the equally wrong "read the
 * whole active table regardless of what was asked for" path.
 */
@Component
public class SpinCandidateEnricher {

    private static final Logger log = LoggerFactory.getLogger(SpinCandidateEnricher.class);

    /** Enrichment cap per run — matches the other hunters; each row costs several Agora calls. */
    static final int MAX = 25;
    /** Non-terminal scan bound for the work queue (spin-offs are rare). */
    private static final int SCAN_LIMIT = 1000;
    /** Rows returned to the LLM per fetch. A full page is reported as truncated (see
     *  {@link #payload(LocalDate)}) — the cap must never look like "that was all there was". */
    static final int RESPONSE_LIMIT = 50;
    /** How often {@link #captureTerms} retries a row whose term sheet is still due — the throttle
     *  that keeps a batch from re-fetching the same filing on every single enrichment run. */
    static final long TERMS_RECHECK_DAYS = 7;

    private final SpinCandidateRepository repo;
    private final SpinLifecycleReconciler reconciler;
    private final SpinBalanceSheetSnapshotter balanceSheet;
    private final SpinDistributionSnapshotter distribution;
    private final SpinValuationSnapshotter valuation;
    private final AgoraFilings filings;
    private final SpinTermsParser termsParser;
    private final ObjectMapper mapper;

    public SpinCandidateEnricher(SpinCandidateRepository repo,
                                 SpinLifecycleReconciler reconciler,
                                 SpinBalanceSheetSnapshotter balanceSheet,
                                 SpinDistributionSnapshotter distribution,
                                 SpinValuationSnapshotter valuation,
                                 AgoraFilings filings,
                                 SpinTermsParser termsParser,
                                 ObjectMapper mapper) {
        this.repo = repo;
        this.reconciler = reconciler;
        this.balanceSheet = balanceSheet;
        this.distribution = distribution;
        this.valuation = valuation;
        this.filings = filings;
        this.termsParser = termsParser;
        this.mapper = mapper;
    }

    /** Per-batch strict-source health; a source marked down is not queried again this batch. */
    private static final class SourceHealth {
        boolean conceptDown;
        boolean ownerHistoryDown;
        boolean skipAll() { return conceptDown && ownerHistoryDown; }
    }

    public void enrich(SpinLifecycleReconciler.ReconcileResult reconcile) {
        enrich(reconcile, LocalDate.now());
    }

    /** Package-private date seam for deterministic tests. */
    void enrich(SpinLifecycleReconciler.ReconcileResult reconcile, LocalDate today) {
        List<SpinCandidateRow> queue = selectQueue(reconcile);
        SourceHealth health = new SourceHealth();
        // Logged once, not once per row, so a long-running outage doesn't spam the log — but the
        // loop itself must NOT stop: see the fix-round-1 finding below.
        boolean loggedSkipAll = false;
        for (SpinCandidateRow row : queue) {
            try {
                // Term capture runs for EVERY row, unconditionally of source health — it does not
                // touch the strict XBRL/owner-history sources at all, so a batch that already gave
                // up on those must not give up on this too (capturesEvenWhenBothStrictSourcesAreDown).
                captureTerms(row, today);

                // ONLY the strict-source status switch is skipped once both sources are down — the
                // loop itself keeps going, so term capture still reaches every later row. Fix-round-1
                // finding: a `break` here (the plan's original wording) silently cost term capture
                // for every row behind the one that tripped the guard, up to MAX-1 rows on an
                // XBRL/EDGAR outage — the exact source/term-sheet coupling this task exists to undo.
                if (health.skipAll()) {
                    if (!loggedSkipAll) {
                        log.info("spin enrichment: both strict sources down, skipping their status-specific "
                                + "work for the rest of this batch (term capture keeps running per row)");
                        loggedSkipAll = true;
                    }
                    repo.touchLastChecked(row.id());
                    continue;
                }
                switch (row.status()) {
                    case REGISTERED, WHEN_ISSUED -> enrichPreDistribution(row, health);
                    case DISTRIBUTED -> enrichDistributed(row, today, health);
                    default -> repo.touchLastChecked(row.id());
                }
            } catch (RuntimeException e) {
                // Belt-and-braces: an unforeseen failure degrades one row, never the run. Stamps
                // BOTH clocks — terms_checked_at too, so a row whose capture attempt itself threw
                // (e.g. the 20.5 MB exhibit) still arms the 7-day throttle instead of being retried
                // on every subsequent run.
                log.debug("spin enrichment: row {} failed: {}", row.id(), e.getMessage());
                repo.touchLastChecked(row.id());
                repo.touchTermsChecked(row.id());
            }
        }
    }

    /**
     * Captures the term-sheet text + parsed ratio + best-effort parent ticker once per
     * {@code TERMS_RECHECK_DAYS} window, for REGISTERED, WHEN_ISSUED and DISTRIBUTED rows alike.
     *
     * <p><b>Due check.</b> {@code recordDate()}/{@code distributionDate()} being null is no longer a
     * useful precondition by itself: since D2, {@link SpinTermsParser} always returns null for both,
     * so on its own this clause would never turn false again. {@code termsCheckedAt} is what actually
     * throttles the retry to once every {@link #TERMS_RECHECK_DAYS} days.
     *
     * <p><b>Two guards against destroying good data (Fix 2/3 traps).</b> A fetch failure must never
     * call {@link SpinCandidateRepository#storeTerms} — that method overwrites ratio, text AND
     * parent_symbol unconditionally, so a transient EDGAR outage would null out a row that already
     * had good data. Likewise, a fetch that SUCCEEDS but resolves to the Form-10 shell instead of the
     * requested exhibit (no {@code EX-99.1} on the index page) must not overwrite a row that already
     * holds real term-sheet text — that is a successful fetch carrying the wrong document, not
     * "no answer". Both cases still stamp {@code terms_checked_at} via
     * {@link SpinCandidateRepository#touchTermsChecked} so the throttle arms regardless of outcome.
     * A row with no text yet DOES accept the shell fallback (better than nothing).
     *
     * <p>{@code today} is the injected date seam (same one {@link #enrich} takes) rather than
     * {@link Instant#now()} directly, so the 7-day due-check is deterministic under test.
     */
    private void captureTerms(SpinCandidateRow row, LocalDate today) {
        Instant cutoff = today.atStartOfDay(ZoneOffset.UTC).toInstant().minus(TERMS_RECHECK_DAYS, ChronoUnit.DAYS);
        boolean due = row.recordDate() == null && row.distributionDate() == null
                && (row.termsCheckedAt() == null || row.termsCheckedAt().isBefore(cutoff));
        if (!due) return;

        FilingText ft = safeFilingText(row.filingUrl());
        if (!ft.available()) {
            repo.touchTermsChecked(row.id());
            return;
        }
        if (row.termSheetAvailable() && ft.resolvedExhibit() == null) {
            // Successful fetch, wrong document: the row already has real text, don't clobber it
            // with the Form-10 shell.
            repo.touchTermsChecked(row.id());
            return;
        }

        String text = ft.text();
        // A "successful" fetch that came back blank must not permanently mark the row as having a
        // term sheet — that would then block the shell-fallback guard above forever, for a row that
        // in truth has no usable text at all (pre-existing defect, fixed alongside this task).
        boolean hasText = text != null && !text.isBlank();
        SpinTerms terms = termsParser.parse(text);
        String parent = resolveParent(termsParser.parentTicker(text), row.symbol());
        repo.storeTerms(row.id(), terms.distributionRatio(), terms.recordDate(),
                terms.distributionDate(), hasText, text, parent);
    }

    /** (transitioned this run) first, then non-terminal rows oldest-checked, deduped, capped. */
    private List<SpinCandidateRow> selectQueue(SpinLifecycleReconciler.ReconcileResult reconcile) {
        List<SpinCandidateRow> nonTerminal = repo.findNonTerminalOldestCheckedFirst(SCAN_LIMIT);
        // findNonTerminalOldestCheckedFirst already orders by last_checked_at ASC; a stable sort
        // that only lifts freshly-transitioned rows to the front preserves that secondary order.
        List<SpinCandidateRow> ordered = new ArrayList<>(nonTerminal);
        ordered.sort(Comparator.comparingInt(r -> reconcile.transitionedIds().contains(r.id()) ? 0 : 1));
        return ordered.size() > MAX ? ordered.subList(0, MAX) : ordered;
    }

    private void enrichPreDistribution(SpinCandidateRow row, SourceHealth health) {
        boolean touched = false;

        // Term capture now happens once per row, up front in enrich() — see captureTerms().

        // Pre-distribution balance sheet (XBRL by CIK; strict concept source).
        if (hasIdentifier(row) && !health.conceptDown) {
            try {
                var snap = balanceSheet.snapshot(row.symbol(), row.cik());
                repo.storeSnapshot(row.id(), SpinStatus.REGISTERED, mapper.valueToTree(snap));
                touched = true;
            } catch (AgoraUnavailableException e) {
                health.conceptDown = true;
                log.warn("spin enrichment: concept source down ({}), skipping it for remaining rows", e.getMessage());
            } catch (RuntimeException e) {
                log.debug("spin enrichment: balance sheet unavailable for row {}: {}", row.id(), e.getMessage());
            }
        }
        if (!touched) repo.touchLastChecked(row.id());
    }

    private void enrichDistributed(SpinCandidateRow row, LocalDate today, SourceHealth health) {
        boolean touched = false;

        // Settlement probe — ONE Assets fetch (strict concept source). PURE predicate only; the
        // SETTLED CAS is deferred until the valuation snapshot is secured (Fix 4).
        boolean settled = false;
        if (hasIdentifier(row) && !health.conceptDown) {
            try {
                ConceptSeries assets = filings.conceptStrict(row.symbol(), row.cik(), "Assets");
                settled = reconciler.isSettled(row, assets, today);
            } catch (AgoraUnavailableException e) {
                health.conceptDown = true;
                log.warn("spin enrichment: concept source down ({}), skipping it for remaining rows", e.getMessage());
            } catch (RuntimeException e) {
                log.debug("spin enrichment: settlement probe failed for row {}: {}", row.id(), e.getMessage());
            }
        }

        if (settled) {
            // Fetch the valuation snapshot FIRST, then commit the (terminal) SETTLED transition and
            // store the snapshot — so a transient valuation-fetch failure leaves the row DISTRIBUTED
            // (retried next run) instead of burning SETTLED with an empty, never-revisited snapshot.
            if (hasIdentifier(row) && !health.conceptDown) {
                try {
                    var snap = valuation.snapshot(row.symbol(), row.cik());
                    if (reconciler.advanceToSettled(row.id())) {
                        repo.storeSnapshot(row.id(), SpinStatus.SETTLED, mapper.valueToTree(snap));
                    }
                    touched = true;
                } catch (AgoraUnavailableException e) {
                    health.conceptDown = true;
                    log.warn("spin enrichment: concept source down ({}), skipping it for remaining rows", e.getMessage());
                } catch (RuntimeException e) {
                    log.debug("spin enrichment: valuation unavailable for row {}: {}", row.id(), e.getMessage());
                }
            }
        } else {
            // Still trading — the size / forced-selling snapshot, keyed on the best-effort parent
            // ticker captured at REGISTERED stage (null when no exchange-qualified ticker was in the
            // term sheet, so the parent/size fields degrade to null; see class javadoc).
            if (!health.ownerHistoryDown) {
                try {
                    // The PROMOTION-WINDOW anchor, not the settlement threshold — see
                    // SpinLifecycleReconciler#promotionAnchorDate javadoc for why the two anchors
                    // must never be merged (HONA/MBGL regression).
                    LocalDate dist = SpinLifecycleReconciler.promotionAnchorDate(row);
                    SpinLifecycleReconciler.AnchorSource anchorSource = SpinLifecycleReconciler.anchorSourceFor(row);
                    var snap = distribution.snapshot(row.symbol(), row.parentSymbol(), dist, anchorSource, today);
                    repo.storeSnapshot(row.id(), SpinStatus.DISTRIBUTED, mapper.valueToTree(snap));
                    touched = true;
                } catch (AgoraUnavailableException e) {
                    health.ownerHistoryDown = true;
                    log.warn("spin enrichment: owner-history source down ({}), skipping it for remaining rows", e.getMessage());
                } catch (RuntimeException e) {
                    log.debug("spin enrichment: distribution snapshot failed for row {}: {}", row.id(), e.getMessage());
                }
            }
        }
        if (!touched) repo.touchLastChecked(row.id());
    }

    /**
     * Builds the LLM payload from the active, unpromoted rows IN THE REQUESTED WINDOW plus their
     * persisted snapshots.
     *
     * <p>{@code since} is what makes the hunt's {@code lookback_days} reach the answer at all.
     * Before this it reached only the EDGAR ingest search while the response was read back from
     * the whole active table, so a 14-day request and a 90-day request produced the identical 8
     * candidates — one of them a filing from ten weeks earlier. See
     * {@link SpinCandidateRepository#findActiveUnpromotedInWindow} for which date the window
     * applies to and why.
     *
     * <p>A FULL page is reported as truncated. That is deliberately the conservative reading: a
     * result that exactly fills the cap may or may not have more behind it, and claiming
     * completeness we cannot verify is the failure this whole change is about.
     */
    public SpinPayload payload(LocalDate since) {
        List<SpinCandidateRow> rows = repo.findActiveUnpromotedInWindow(since, RESPONSE_LIMIT);
        boolean truncated = rows.size() >= RESPONSE_LIMIT;
        if (truncated) {
            log.info("spin payload: response capped at {} rows since {}", RESPONSE_LIMIT, since);
        }
        return new SpinPayload(rows.stream().map(this::toWire).toList(), truncated);
    }

    private EnrichedSpinCandidate toWire(SpinCandidateRow row) {
        JsonNode reg = row.registeredSnapshot();
        JsonNode dist = row.distributedSnapshot();
        JsonNode set = row.settledSnapshot();
        return new EnrichedSpinCandidate(
                row.symbol(), row.companyName(), row.formType(),
                row.filingDate() == null ? null : row.filingDate().toString(),
                row.filingUrl(),
                row.termSheetText(),                    // raw prose, persisted (V26) for the LLM
                row.termSheetAvailable(),
                row.distributionRatio(),
                row.recordDate() == null ? null : row.recordDate().toString(),
                row.distributionDate() == null ? null : row.distributionDate().toString(),
                row.status().name(),
                // REGISTERED
                bd(reg, "totalAssets"), bd(reg, "totalLiabilities"), bd(reg, "retainedEarnings"),
                text(reg, "industry"),
                // DISTRIBUTED
                dbl(dist, "spincoMarketCapMillions"), dbl(dist, "parentMarketCapMillions"),
                dbl(dist, "sizeRatio"), integer(dist, "daysSinceDistribution"),
                boolOrFalse(dist, "distributionDateConfirmed"),
                text(dist, "anchorSource"),
                bool(dist, "postSpinInsiderBuying"),
                // SETTLED
                dbl(set, "priceToBook"), dbl(set, "evToEbit"), dbl(set, "fcfYield"));
    }

    /** Reads the EX-99.1 information statement (Fix, D3), not the Form-10 shell — see
     *  {@link AgoraFilings#filingText(String, String, String)}. */
    private FilingText safeFilingText(String url) {
        try {
            return filings.filingText(url, "EX-99.1", "LEADING");
        } catch (RuntimeException e) {
            log.debug("spin enrichment: filing text unavailable for {}: {}", url, e.getMessage());
            return FilingText.unavailable();
        }
    }

    /** A parent ticker only counts when it is present AND not the spin-co's own symbol (the
     *  information statement can name the spin-co's future ticker too); otherwise null. */
    private static String resolveParent(String parentTicker, String spincoSymbol) {
        if (parentTicker == null || parentTicker.isBlank()) return null;
        if (spincoSymbol != null && parentTicker.equalsIgnoreCase(spincoSymbol.trim())) return null;
        return parentTicker;
    }

    private static boolean hasIdentifier(SpinCandidateRow row) {
        return notBlank(row.cik()) || notBlank(row.symbol());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // --- snapshot JSON readers (nullable, defensive) ---

    private static BigDecimal bd(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode n = node.path(field);
        if (!n.isNumber()) return null;
        try { return new BigDecimal(n.asString()); } catch (RuntimeException e) { return null; }
    }

    private static Double dbl(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode n = node.path(field);
        return n.isNumber() ? n.asDouble() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode n = node.path(field);
        return n.isNumber() ? n.asInt() : null;
    }

    private static Boolean bool(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode n = node.path(field);
        return n.isBoolean() ? n.asBoolean() : null;
    }

    /** Like {@link #bool}, but for a non-nullable snapshot field: absent/wrong-type reads as false
     *  (never a market-fact claim) rather than propagating null. */
    private static boolean boolOrFalse(JsonNode node, String field) {
        if (node == null) return false;
        JsonNode n = node.path(field);
        return n.isBoolean() && n.asBoolean();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode n = node.path(field);
        return n.isTextual() ? n.asString() : null;
    }
}
