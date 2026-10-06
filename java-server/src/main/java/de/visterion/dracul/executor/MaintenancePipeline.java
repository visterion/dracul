package de.visterion.dracul.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * The server-side maintenance orchestrator: reconcile against the broker, apply deterministic
 * hard exits, ratchet trailing stops, then re-read the fresh book and enrich each survivor with
 * the current market/derived state (chandelier level, R, soft-breach confirmation) for the
 * Chronicle position book.
 *
 * <p>Pipeline order is fixed and matters: {@link ReconcileService} must run first (it detects
 * fills/disappearances against the broker before anything else touches the book), then
 * {@link EntryExpiryService} (cancels — never re-prices — unfilled GTD entries past their expiry,
 * using the fill state reconcile just refreshed), then {@link PendingSignalSweeper} (retires
 * PENDING signals past {@code max-signal-age-days} that nobody evaluated), then
 * {@link HardTriggerService} (deterministic
 * exits, code-enforced, never overridden), then {@link StopRatchetService} (trailing-stop
 * maintenance on whatever survived). Then, inside the closed-market window and only for the pass
 * holding the savings lease, {@link SavingsConsolidator} runs before the hard triggers (spec
 * 2026-10-06), {@link HardTriggerService} and {@link StopRatchetService} run with in-flight savings
 * positions excluded (UNPROTECTED ones stop-only), then the STOP_NOT_LIVE invariant and
 * {@link SavingsPlanService#addStage} (in that order: a position whose leg-1 stop is not live gets
 * no add); the savings stale check runs on every pass. Positions whose GTD entry has
 * no confirmed fill yet
 * ({@link ReconcileService.ReconcileResult#unfilledIds()}) are excluded from both the
 * hard-trigger and ratchet steps — they hold nothing at the broker to flatten or ratchet — but
 * remain in the final enrichment so the book stays visible. The final
 * enrichment re-reads {@link ExecutorPositionRepository#findOpen()} rather than reusing the
 * in-memory list, because the ratchet step mutates stops in the DB — but it is intersected with
 * the hard-trigger survivors so positions closed by the hard trigger in this same pass are
 * excluded even though a concurrent run could otherwise reopen the same id.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class MaintenancePipeline {

    private static final Logger log = LoggerFactory.getLogger(MaintenancePipeline.class);

    private final ReconcileService reconcile;
    private final EntryExpiryService entryExpiry;
    private final PendingSignalSweeper sweeper;
    private final HardTriggerService hardTrigger;
    private final StopRatchetService ratchet;
    private final SoftConditionEvaluator softEval;
    private final ExecutorIndicators indicators;
    private final ExecutorPositionRepository positionRepo;
    private final ExecutorSignalRepository signalRepo;
    private final Tranche2Detector tranche2Detector;
    private final ConvictionProfile convictionProfile;
    private final SavingsPlanService savingsPlan;
    private final SavingsConsolidator consolidator;
    private final Clock clock;
    private final double chandelierMult;
    private final int atrPeriod;
    private final int swingPeriod;

    public MaintenancePipeline(
            ReconcileService reconcile,
            EntryExpiryService entryExpiry,
            PendingSignalSweeper sweeper,
            HardTriggerService hardTrigger,
            StopRatchetService ratchet,
            SoftConditionEvaluator softEval,
            ExecutorIndicators indicators,
            ExecutorPositionRepository positionRepo,
            ExecutorSignalRepository signalRepo,
            Tranche2Detector tranche2Detector,
            ConvictionProfile convictionProfile,
            SavingsPlanService savingsPlan,
            SavingsConsolidator consolidator,
            @Qualifier("executorClock") Clock clock,
            @Value("${dracul.executor.chandelier-mult:3.0}") double chandelierMult,
            @Value("${dracul.executor.atr-period:22}") int atrPeriod,
            @Value("${dracul.executor.swing-period:20}") int swingPeriod) {
        this.reconcile = reconcile;
        this.entryExpiry = entryExpiry;
        this.sweeper = sweeper;
        this.hardTrigger = hardTrigger;
        this.ratchet = ratchet;
        this.softEval = softEval;
        this.indicators = indicators;
        this.positionRepo = positionRepo;
        this.signalRepo = signalRepo;
        this.tranche2Detector = tranche2Detector;
        this.convictionProfile = convictionProfile;
        this.savingsPlan = savingsPlan;
        this.consolidator = consolidator;
        this.clock = clock;
        this.chandelierMult = chandelierMult;
        this.atrPeriod = atrPeriod;
        this.swingPeriod = swingPeriod;
    }

    public List<EnrichedPosition> run(String connection, String runId) {
        Instant passStart = clock.instant();
        ReconcileService.ReconcileResult reconciled = reconcile.reconcile(connection, runId);
        List<ExecutorPosition> survivors = reconciled.survivors();
        Set<Long> unfilledIds = reconciled.unfilledIds();

        // The expiry step cancels unfilled GTD entries in the DB; drop those ids from the
        // in-memory reconcile survivors too, or a just-cancelled position could still be
        // hard-triggered/flattened in this same pass.
        Set<Long> expiryCancelledIds = entryExpiry.expire(connection, runId);
        if (!expiryCancelledIds.isEmpty()) {
            survivors = survivors.stream()
                    .filter(p -> !expiryCancelledIds.contains(p.id()))
                    .toList();
        }

        // Retire PENDING signals nobody evaluated, BEFORE the pending read at the bottom of this
        // method: a signal past the age bound must stop conferring REINFORCING_SIGNAL tranche-2
        // eligibility in this same pass. The return value is for tests and the log line; the
        // pipeline itself has nothing to do with it.
        //
        // Guarded: this hygiene step must never be able to gate the deterministic hard-exit and
        // ratchet steps that follow. sweeper.sweep wraps its per-signal body in its own
        // catch (RuntimeException), but findPending's mapRow and the summary log sit outside
        // that — an unmappable row, a statement timeout or a transient pool exhaustion would
        // otherwise throw out of sweep() and abort this method before hardTrigger.apply and
        // ratchet.ratchet ever run, leaving a breached kill criterion unflattened.
        try {
            sweeper.sweep(runId);
        } catch (RuntimeException e) {
            log.warn("pending sweep failed, continuing the maintenance pass: {}", e.getMessage(), e);
        }

        // ---- Tech-Sparplan (spec 2026-10-06 §3.2, §3.4) ---------------------------------------
        // The lease holder is a fresh UUID per run(): Vistierie retries and parallel tool dispatch
        // reuse the run id, so a run-id holder would let every duplicate in (R3 M6). Every savings
        // stage is guarded like the sweep — an exception must never cost the hard triggers.
        String pass = UUID.randomUUID().toString();
        Instant now = clock.instant();
        boolean leased = acquireSavingsLease(pass, now, runId);
        try {
            if (leased) {
                try {
                    consolidator.consolidateStage(connection, runId, pass, now);
                } catch (RuntimeException e) {
                    log.warn("savings-plan stage failed stage=consolidate run={} pass={} error={}", runId, pass, e.toString(), e);
                }
            }
            // §5.3 SAVINGS_ADD_STALE: every pass, no window, no lease (once per row inside).
            try {
                savingsPlan.staleCheck(connection, runId, pass, now);
            } catch (RuntimeException e) {
                log.warn("savings-plan stage failed stage=stale run={} pass={} error={}", runId, pass, e.toString(), e);
            }
            // Consolidation (this pass or a parallel one) changed qty / stops: re-read those survivors.
            // No savings read may ever keep the hard triggers from running: every read below fails
            // towards "triggers run", never towards an aborted pass.
            survivors = rereadTouched(survivors, passStart, runId, pass);
            Map<Long, String> inFlight;
            String exclusionReason;
            try {
                inFlight = savingsPlan.inFlightStatuses();
                exclusionReason = inFlight.isEmpty() ? "nothing-in-flight" : "savings-add-in-flight";
            } catch (RuntimeException e) {
                // In-flight state unknown: any CONVICTION position might have a BUY resting. Treat
                // them all as stop-only — stop breach and catastrophe run, no TARGET_HALF (which
                // could trim under a resting add) and no ratchet. Excluding them entirely would
                // blind the stop; excluding none would allow the half-sale.
                log.warn("savings-plan stage failed stage=exclusion run={} pass={} error={}", runId, pass, e.toString(), e);
                inFlight = new LinkedHashMap<>();
                for (ExecutorPosition p : survivors) {
                    if (p.profile() == ExitProfile.CONVICTION && "OPEN".equals(p.status())) {
                        inFlight.put(p.id(), SavingsBuy.UNPROTECTED);
                    }
                }
                exclusionReason = "in-flight-unknown";
            }
            Set<Long> excludedInFlight = new LinkedHashSet<>();
            Set<Long> stopOnly = new LinkedHashSet<>();
            for (Map.Entry<Long, String> e : inFlight.entrySet()) {
                if (SavingsBuy.UNPROTECTED.equals(e.getValue())) stopOnly.add(e.getKey());
                else excludedInFlight.add(e.getKey());
            }
            // §8a: once per pass, also when nothing is in flight, so "no exclusion" is visible.
            log.info("savings-plan exclusion run={} pass={} excluded={} stop_only={} reason={}",
                    runId, pass, describe(survivors, excludedInFlight, inFlight),
                    describe(survivors, stopOnly, inFlight), exclusionReason);

            // Only positions that will actually be evaluated below (filled, no pending exit) can
            // have a hard-trigger/ratchet check "silently skipped" by a missing indicator — an
            // unfilled or already-pending-exit position was never going to be checked this run
            // regardless (see the gating below), so a missing indicator for it must not be reported
            // as a skipped safety check. Computed here, before the maps, only to know which symbols
            // are eligible to be named in the warning below — it does not change which survivors the
            // maps are built from.
            Set<Long> uncheckedIds = new HashSet<>();
            for (ExecutorPosition p : survivors) {
                if (unfilledIds.contains(p.id()) || p.pendingExitReason() != null) uncheckedIds.add(p.id());
            }
            // An in-flight savings add keeps its position out of the triggers below (spec §6.2).
            uncheckedIds.addAll(excludedInFlight);

            Map<String, BigDecimal> closeBySymbol = new HashMap<>();
            Map<String, BigDecimal> atrBySymbol = new HashMap<>();
            // atrEff = max(atr22, atr_short) drives the chandelier and the broker-stop buffer;
            // atr_short rides along for the ratchet snapshot and the LLM view. All three come from the
            // SAME Levels object -- one indicators.levels call per symbol, unchanged.
            Map<String, BigDecimal> atrShortBySymbol = new HashMap<>();
            Map<String, BigDecimal> atrEffBySymbol = new HashMap<>();
            Set<String> withoutIndicators = new LinkedHashSet<>();
            Set<String> withoutShortAtr = new LinkedHashSet<>();
            // Both n and total must count the same thing — distinct SYMBOLS, matching the word in
            // the message — not positions. Two filled positions sharing one unavailable symbol is
            // one unavailable symbol out of however many distinct symbols were checked, not "1 of 2":
            // the line feeds an alarm rule later and a positions/symbols mismatch would understate
            // severity exactly when a shared symbol is the one that is down.
            Set<String> checkedSymbols = new LinkedHashSet<>();
            for (ExecutorPosition p : survivors) {
                ExecutorIndicators.Levels lv = indicators.levels(p.symbol(), atrPeriod, swingPeriod);
                boolean wasGoingToBeChecked = !uncheckedIds.contains(p.id());
                if (wasGoingToBeChecked) checkedSymbols.add(p.symbol());
                if (!lv.available()) {
                    if (wasGoingToBeChecked) withoutIndicators.add(p.symbol());
                    continue;
                }
                if (lv.referencePrice() != null) closeBySymbol.put(p.symbol(), lv.referencePrice());
                if (lv.atr() != null) atrBySymbol.put(p.symbol(), lv.atr());
                if (lv.atrShort() != null) atrShortBySymbol.put(p.symbol(), lv.atrShort());
                else if (wasGoingToBeChecked) withoutShortAtr.add(p.symbol());
                if (lv.atrEff() != null) atrEffBySymbol.put(p.symbol(), lv.atrEff());
            }
            // A symbol missing here is missing from BOTH maps, which disables the stop ratchet AND
            // the hard-trigger evaluation for that position for this entire run. The skip itself is
            // correct — without an ATR there is nothing to compute — but it used to be invisible,
            // so a provider outage looked exactly like a quiet pass. ONE line, not one per symbol:
            // a total outage would otherwise emit a line per position and drown the signal. A
            // LinkedHashSet, not a list: two open positions sharing a symbol must not double-count
            // it, and order stays stable for a deterministic message.
            if (!withoutIndicators.isEmpty()) {
                log.warn("maintenance indicators unavailable: {} of {} symbols — {}",
                        withoutIndicators.size(), checkedSymbols.size(),
                        String.join(",", withoutIndicators));
            }

            // Fail-soft and separate from the outage line above: a missing short ATR is DATA (the
            // symbol has too few bars), not an outage, and atrEff simply falls back to ATR22. One line
            // per run, same stable prefix family so the existing alarm rule sees it.
            if (!withoutShortAtr.isEmpty()) {
                log.warn("maintenance indicators unavailable: atr_short for {} of {} symbols — {}",
                        withoutShortAtr.size(), checkedSymbols.size(),
                        String.join(",", withoutShortAtr));
            }

            // Hard triggers and stop ratcheting act on broker holdings — a position whose GTD
            // entry never filled has none, so a breached kill criterion / stop level on it must
            // NOT flatten or close anything (EntryExpiryService owns that lifecycle). Unfilled
            // positions are excluded here but kept for the final enrichment, so the book stays
            // visible to the agent.
            //
            // A row already carrying pendingExitReason (a prior hard-trigger flatten or fill-less
            // webhook FULL exit, not yet confirmed by the broker) must likewise be excluded: it has
            // already submitted its one flatten/close order for this exit, so evaluating hard
            // triggers or ratcheting its stop again this same run would risk a double-flatten.
            // ReconcileService's own survivor loop is the only thing allowed to touch it further
            // (finalize-or-keep), which already ran above this line. Filtering directly on the field
            // (rather than threading a new id set through ReconcileResult) needs no extra plumbing —
            // pendingExitReason is already carried on every ExecutorPosition.
            List<ExecutorPosition> filledSurvivors = survivors.stream()
                    .filter(p -> !unfilledIds.contains(p.id()))
                    .filter(p -> p.pendingExitReason() == null)
                    .filter(p -> !excludedInFlight.contains(p.id()))
                    .toList();

            // Spec 2026-10-06 §6.2: an in-flight savings add (a BUY resting at the broker) must see no
            // TARGET_HALF and no flatten — Agora's flatten cancels only opposite-side orders, so the
            // add could still fill into a trimmed or closed book. UNPROTECTED is the exception: its
            // stops are being rebuilt, so the stop breach and the catastrophe run (no TARGET_HALF, no
            // ratchet).
            List<ExecutorPosition> afterHard = stopOnly.isEmpty()
                    ? hardTrigger.apply(filledSurvivors, closeBySymbol, runId)
                    : hardTrigger.apply(filledSurvivors, closeBySymbol, runId, stopOnly);
            ratchet.ratchet(stopOnly.isEmpty() ? afterHard
                            : afterHard.stream().filter(p -> !stopOnly.contains(p.id())).toList(),
                    atrBySymbol, atrShortBySymbol, atrEffBySymbol, closeBySymbol, runId);

            // The gap since the consolidate stage (indicators, triggers, ratchet broker writes) can be
            // long: renew before the add, and if the lease is gone a parallel pass may own it now.
            if (leased && !renewSavingsLease(pass, now, runId)) {
                for (String stage : List.of("stop-live", "add")) {
                    log.info("savings-plan stage stage={} run={} pass={} outcome=did-nothing why=lease-lost now={}",
                            stage, runId, pass, now);
                }
            } else if (leased) {
                // STOP_NOT_LIVE runs BEFORE the add (final review I1): an add makes its position in
                // flight — out of the hard triggers and out of this very check — on the premise that
                // the leg-1 stop protects the shares. A position whose leg-1 stop is not live must
                // therefore never get an add; null = the check failed, so no stop is known live.
                Map<Long, String> stopNotLive;
                try {
                    stopNotLive = savingsPlan.checkStopsLive(connection, runId, pass);
                } catch (RuntimeException e) {
                    log.warn("savings-plan stage failed stage=stop-live run={} pass={} error={}", runId, pass, e.toString(), e);
                    stopNotLive = null;
                }
                try {
                    savingsPlan.addStage(connection, runId, pass, now, stopNotLive);
                } catch (RuntimeException e) {
                    log.warn("savings-plan stage failed stage=add run={} pass={} error={}", runId, pass, e.toString(), e);
                }
            }

            Set<Long> keepIds = new HashSet<>();
            for (ExecutorPosition p : afterHard) keepIds.add(p.id());
            for (ExecutorPosition p : survivors) {
                if (unfilledIds.contains(p.id()) || p.pendingExitReason() != null
                        || excludedInFlight.contains(p.id())) keepIds.add(p.id());
            }

            List<ExecutorPosition> finalOpen = positionRepo.findOpen().stream()
                    .filter(p -> connection.equals(p.connection()))
                    .filter(p -> keepIds.contains(p.id()))
                    .toList();

            List<ExecutorSignal> pendings = signalRepo.findPending(50);

            List<EnrichedPosition> enriched = new ArrayList<>();
            for (ExecutorPosition p : finalOpen) {
                BigDecimal currentPrice = closeBySymbol.get(p.symbol());
                String positionMechanism = resolveMechanism(p.sourceSignalId());
                Tranche2Detector.Tranche2Status t2 = tranche2Detector.detect(p, currentPrice, pendings, positionMechanism);
                boolean entryFilled = !unfilledIds.contains(p.id());
                enriched.add(enrich(p, currentPrice, atrBySymbol.get(p.symbol()),
                        atrShortBySymbol.get(p.symbol()), t2, entryFilled));
            }
            return enriched;
        } finally {
            if (leased) {
                try {
                    savingsPlan.releaseLease(pass);
                } catch (RuntimeException e) {
                    // Never mask the pass result: the lease expires on its own after 10 minutes.
                    log.warn("savings-plan stage failed stage=release run={} pass={} error={}", runId, pass, e.toString(), e);
                }
            }
        }
    }

    /** Logs why both savings stages do nothing when there is no window or no lease (spec §8a: a stage
     *  that does nothing says why). @return true when this pass holds the savings lease */
    private boolean acquireSavingsLease(String pass, Instant now, String runId) {
        String why;
        try {
            if (!savingsPlan.inWindow(now)) {
                why = "outside-window";
            } else if (savingsPlan.tryLease(pass)) {
                return true;
            } else {
                String holder = savingsPlan.leaseHolder();
                // The holder's lease can expire between our failed acquire and this read.
                why = holder == null ? "lease-expired-race" : "lease-held-by:" + holder;
            }
        } catch (RuntimeException e) {
            // A tryLease that committed and then threw leaves the lease for at most 10 minutes —
            // acceptable; losing the hard triggers is not.
            log.warn("savings-plan stage failed stage=lease run={} pass={} error={}", runId, pass, e.toString(), e);
            why = "lease-error";
        }
        for (String stage : List.of("consolidate", "add", "stop-live")) {
            log.info("savings-plan stage stage={} run={} pass={} outcome=did-nothing why={} now={}",
                    stage, runId, pass, why, now);
        }
        return false;
    }

    /** Renews the lease before the add stage. @return false when the lease is lost (or the renew
     *  failed) — the remaining savings stages are then skipped. */
    private boolean renewSavingsLease(String pass, Instant now, String runId) {
        try {
            return savingsPlan.renewLease(pass);
        } catch (RuntimeException e) {
            log.warn("savings-plan stage failed stage=renew run={} pass={} error={}", runId, pass, e.toString(), e);
            return false;
        }
    }

    /** Spec §3.2: survivors whose savings row changed since the pass started (this pass's or a parallel
     *  pass's consolidation) are re-read; a row no longer OPEN is dropped. If the touched set cannot be
     *  read, every survivor is re-read; a single failing re-read keeps the in-memory row (never drop a
     *  position — its triggers would be lost). */
    private List<ExecutorPosition> rereadTouched(List<ExecutorPosition> survivors, Instant passStart,
            String runId, String pass) {
        Set<Long> touched;
        try {
            touched = savingsPlan.positionsTouchedSince(passStart.minusSeconds(60));
            if (touched == null || touched.isEmpty()) return survivors;
        } catch (RuntimeException e) {
            log.warn("savings-plan stage failed stage=reread run={} pass={} error={}", runId, pass, e.toString(), e);
            touched = null;
        }
        List<ExecutorPosition> out = new ArrayList<>();
        for (ExecutorPosition p : survivors) {
            if (touched != null && !touched.contains(p.id())) {
                out.add(p);
                continue;
            }
            ExecutorPosition fresh;
            try {
                fresh = positionRepo.findById(p.id());
            } catch (RuntimeException e) {
                log.warn("savings-plan row failed stage=reread position={} symbol={} run={} pass={} error={}",
                        p.id(), p.symbol(), runId, pass, e.toString(), e);
                out.add(p);
                continue;
            }
            if (fresh != null && "OPEN".equals(fresh.status())) out.add(fresh);
        }
        return out;
    }

    private static String describe(List<ExecutorPosition> survivors, Set<Long> ids, Map<Long, String> status) {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (ExecutorPosition p : survivors) {
            if (ids.contains(p.id())) j.add(p.id() + ":" + p.symbol() + ":" + status.get(p.id()));
        }
        return j.toString();
    }

    private EnrichedPosition enrich(ExecutorPosition p, BigDecimal currentPrice, BigDecimal atr,
            BigDecimal atrShort, Tranche2Detector.Tranche2Status t2, boolean entryFilled) {
        boolean sell = "SELL".equals(p.side());

        // Wide-stop profiles (CONVICTION, MOMENTUM) have no soft trigger: their exits are code (spec 2026-10-03 §5.6, 2026-10-04 §3).
        boolean wideStop = p.profile().isWideStop();

        // ATR22, deliberately NOT atrEff. The soft trigger is meant to fire EARLIER than the hard
        // stop; widening it with a post-report window would silence it.
        BigDecimal chandelierLevel = null;
        if (!wideStop && currentPrice != null && atr != null && p.highestPrice() != null) {
            BigDecimal offset = atr.multiply(BigDecimal.valueOf(chandelierMult));
            chandelierLevel = sell ? p.highestPrice().add(offset) : p.highestPrice().subtract(offset);
        }

        BigDecimal rCurrent = computeR(p, currentPrice, sell);

        // Soft-confirm accumulation only makes sense on real holdings: an unfilled entry has
        // nothing to soft-exit, so its confirm count must not creep up while the order waits
        // for its fill (it would prime an immediate soft exit the moment the entry fills).
        SoftConditionEvaluator.SoftState ss = entryFilled && !wideStop
                ? softEval.evaluate(currentPrice, chandelierLevel,
                        null, null, p.side(), p.softConfirmCount())
                : new SoftConditionEvaluator.SoftState(false, false,
                        wideStop ? 0 : p.softConfirmCount());

        // Context only: the structured kill level, never a parse of the free-text kill_criteria.
        // For a FILLED position a breach is flattened by HardTriggerService earlier in this same
        // pass, so this is mostly visible on unfilled / pending-exit / failed-flatten rows.
        // Kill levels are STANDARD only (spec 2026-10-03 §5.4 #3), so a wide-stop row never
        // carries one here either.
        String killBreach = wideStop ? null : HardTriggerService.killLevelBreach(p, currentPrice);
        List<String> killCriteriaBreached = killBreach == null ? List.of() : List.of(killBreach);

        positionRepo.updateMaintenance(p.id(), p.highestPrice(), p.mfeR(), ss.confirmCount(),
                p.activeStop(), null, p.brokerStop());

        // Adverse-extreme (MAE) tracking: BUY positions track the lowest close seen while open,
        // written only when it decreases below the current floor. SELL positions do NOT write
        // lowest_price — their adverse extreme is the HIGHEST close, already tracked as
        // highestPrice via the ratchet step; mae_r for SELL positions derives from highest_price.
        // Gated on entryFilled: a pre-fill close is not an excursion of any held position.
        if (entryFilled && !sell && currentPrice != null) {
            BigDecimal floor = p.lowestPrice() != null ? p.lowestPrice() : p.entryPrice();
            if (currentPrice.compareTo(floor) < 0) {
                positionRepo.updateAdverseExtreme(p.id(), currentPrice);
            }
        }

        return new EnrichedPosition(p.id(), p.connection(), p.symbol(), p.side(), p.qty(),
                p.entryPrice(), p.activeStop(), currentPrice, atr, chandelierLevel, rCurrent,
                p.mfeR(), daysHeld(p.entryDate()), p.killCriteria(), killCriteriaBreached,
                ss.chandelierBreach(), ss.maBreak(), ss.confirmCount(), t2.eligible(), t2.reason(),
                p.sourceSignalId(), p.trimCount(), ExecutorWebhookController.ladderFloor(p.trimCount()),
                entryFilled, atrShort, p.brokerStop(), p.killCloseBelow(), p.killCloseBelowDropped(),
                p.exitProfile());
    }

    private String resolveMechanism(String sourceSignalId) {
        if (sourceSignalId == null) return null;
        ExecutorSignal source = signalRepo.findById(sourceSignalId);
        return source == null ? null : source.mechanism();
    }

    private BigDecimal computeR(ExecutorPosition p, BigDecimal currentPrice, boolean sell) {
        if (currentPrice == null) return null;
        BigDecimal denominator = RiskPerShare.of(p, convictionProfile);
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) return null;
        BigDecimal numerator = sell
                ? p.entryPrice().subtract(currentPrice)
                : currentPrice.subtract(p.entryPrice());
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private long daysHeld(String entryDate) {
        if (entryDate == null || entryDate.isBlank()) return 0;
        try {
            LocalDate entry = LocalDate.parse(entryDate.length() > 10 ? entryDate.substring(0, 10) : entryDate);
            LocalDate today = Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
            return Duration.between(entry.atStartOfDay(), today.atStartOfDay()).toDays();
        } catch (Exception e) {
            return 0;
        }
    }
}
