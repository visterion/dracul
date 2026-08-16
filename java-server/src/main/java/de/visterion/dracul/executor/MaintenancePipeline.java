package de.visterion.dracul.executor;

import de.visterion.dracul.criteria.KillCriteriaEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The server-side maintenance orchestrator: reconcile against the broker, apply deterministic
 * hard exits, ratchet trailing stops, then re-read the fresh book and enrich each survivor with
 * the current market/derived state (chandelier level, R, soft-breach confirmation) for the
 * Chronicle position book.
 *
 * <p>Pipeline order is fixed and matters: {@link ReconcileService} must run first (it detects
 * fills/disappearances against the broker before anything else touches the book), then
 * {@link EntryExpiryService} (cancels — never re-prices — unfilled GTD entries past their expiry,
 * using the fill state reconcile just refreshed), then {@link HardTriggerService} (deterministic
 * exits, code-enforced, never overridden), then {@link StopRatchetService} (trailing-stop
 * maintenance on whatever survived). Positions whose GTD entry has no confirmed fill yet
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
    private final HardTriggerService hardTrigger;
    private final StopRatchetService ratchet;
    private final SoftConditionEvaluator softEval;
    private final ExecutorIndicators indicators;
    private final ExecutorPositionRepository positionRepo;
    private final ExecutorSignalRepository signalRepo;
    private final Tranche2Detector tranche2Detector;
    private final KillCriteriaEvaluator killCriteriaEvaluator;
    private final double chandelierMult;
    private final int atrPeriod;
    private final int swingPeriod;

    public MaintenancePipeline(
            ReconcileService reconcile,
            EntryExpiryService entryExpiry,
            HardTriggerService hardTrigger,
            StopRatchetService ratchet,
            SoftConditionEvaluator softEval,
            ExecutorIndicators indicators,
            ExecutorPositionRepository positionRepo,
            ExecutorSignalRepository signalRepo,
            Tranche2Detector tranche2Detector,
            KillCriteriaEvaluator killCriteriaEvaluator,
            @Value("${dracul.executor.chandelier-mult:3.0}") double chandelierMult,
            @Value("${dracul.executor.atr-period:22}") int atrPeriod,
            @Value("${dracul.executor.swing-period:20}") int swingPeriod) {
        this.reconcile = reconcile;
        this.entryExpiry = entryExpiry;
        this.hardTrigger = hardTrigger;
        this.ratchet = ratchet;
        this.softEval = softEval;
        this.indicators = indicators;
        this.positionRepo = positionRepo;
        this.signalRepo = signalRepo;
        this.tranche2Detector = tranche2Detector;
        this.killCriteriaEvaluator = killCriteriaEvaluator;
        this.chandelierMult = chandelierMult;
        this.atrPeriod = atrPeriod;
        this.swingPeriod = swingPeriod;
    }

    public List<EnrichedPosition> run(String connection, String runId) {
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

        Map<String, BigDecimal> closeBySymbol = new HashMap<>();
        Map<String, BigDecimal> atrBySymbol = new HashMap<>();
        Set<String> withoutIndicators = new LinkedHashSet<>();
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
                .toList();

        List<ExecutorPosition> afterHard = hardTrigger.apply(filledSurvivors, closeBySymbol, runId);
        ratchet.ratchet(afterHard, atrBySymbol, closeBySymbol, runId);

        Set<Long> keepIds = new HashSet<>();
        for (ExecutorPosition p : afterHard) keepIds.add(p.id());
        for (ExecutorPosition p : survivors) {
            if (unfilledIds.contains(p.id()) || p.pendingExitReason() != null) keepIds.add(p.id());
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
            enriched.add(enrich(p, currentPrice, atrBySymbol.get(p.symbol()), t2, entryFilled));
        }
        return enriched;
    }

    private EnrichedPosition enrich(ExecutorPosition p, BigDecimal currentPrice, BigDecimal atr,
            Tranche2Detector.Tranche2Status t2, boolean entryFilled) {
        boolean sell = "SELL".equals(p.side());

        BigDecimal chandelierLevel = null;
        if (currentPrice != null && atr != null && p.highestPrice() != null) {
            BigDecimal offset = atr.multiply(BigDecimal.valueOf(chandelierMult));
            chandelierLevel = sell ? p.highestPrice().add(offset) : p.highestPrice().subtract(offset);
        }

        BigDecimal rCurrent = computeR(p, currentPrice, sell);

        // Soft-confirm accumulation only makes sense on real holdings: an unfilled entry has
        // nothing to soft-exit, so its confirm count must not creep up while the order waits
        // for its fill (it would prime an immediate soft exit the moment the entry fills).
        SoftConditionEvaluator.SoftState ss = entryFilled
                ? softEval.evaluate(currentPrice, chandelierLevel,
                        null, null, p.side(), p.softConfirmCount())
                : new SoftConditionEvaluator.SoftState(false, false, p.softConfirmCount());

        List<String> killCriteriaBreached = killCriteriaEvaluator.breached(p.killCriteria(), currentPrice);

        positionRepo.updateMaintenance(p.id(), p.highestPrice(), p.mfeR(), ss.confirmCount(),
                p.activeStop(), null);

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
                entryFilled);
    }

    private String resolveMechanism(String sourceSignalId) {
        if (sourceSignalId == null) return null;
        ExecutorSignal source = signalRepo.findById(sourceSignalId);
        return source == null ? null : source.mechanism();
    }

    private BigDecimal computeR(ExecutorPosition p, BigDecimal currentPrice, boolean sell) {
        if (currentPrice == null) return null;
        BigDecimal numerator;
        BigDecimal denominator;
        if (sell) {
            numerator = p.entryPrice().subtract(currentPrice);
            denominator = p.initialStop().subtract(p.entryPrice());
        } else {
            numerator = currentPrice.subtract(p.entryPrice());
            denominator = p.entryPrice().subtract(p.initialStop());
        }
        if (denominator.compareTo(BigDecimal.ZERO) == 0) return null;
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
