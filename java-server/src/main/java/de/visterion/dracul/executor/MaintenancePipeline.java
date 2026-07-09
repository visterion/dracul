package de.visterion.dracul.executor;

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
 * {@link HardTriggerService} (deterministic exits, code-enforced, never overridden), then
 * {@link StopRatchetService} (trailing-stop maintenance on whatever survived). The final
 * enrichment re-reads {@link ExecutorPositionRepository#findOpen()} rather than reusing the
 * in-memory list, because the ratchet step mutates stops in the DB — but it is intersected with
 * the hard-trigger survivors so positions closed by the hard trigger in this same pass are
 * excluded even though a concurrent run could otherwise reopen the same id.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class MaintenancePipeline {

    private final ReconcileService reconcile;
    private final HardTriggerService hardTrigger;
    private final StopRatchetService ratchet;
    private final SoftConditionEvaluator softEval;
    private final ExecutorIndicators indicators;
    private final ExecutorPositionRepository positionRepo;
    private final double chandelierMult;
    private final int atrPeriod;
    private final int swingPeriod;

    public MaintenancePipeline(
            ReconcileService reconcile,
            HardTriggerService hardTrigger,
            StopRatchetService ratchet,
            SoftConditionEvaluator softEval,
            ExecutorIndicators indicators,
            ExecutorPositionRepository positionRepo,
            @Value("${dracul.executor.chandelier-mult:3.0}") double chandelierMult,
            @Value("${dracul.executor.atr-period:22}") int atrPeriod,
            @Value("${dracul.executor.swing-period:20}") int swingPeriod) {
        this.reconcile = reconcile;
        this.hardTrigger = hardTrigger;
        this.ratchet = ratchet;
        this.softEval = softEval;
        this.indicators = indicators;
        this.positionRepo = positionRepo;
        this.chandelierMult = chandelierMult;
        this.atrPeriod = atrPeriod;
        this.swingPeriod = swingPeriod;
    }

    public List<EnrichedPosition> run(String connection, String runId) {
        List<ExecutorPosition> survivors = reconcile.reconcile(connection, runId);

        Map<String, BigDecimal> closeBySymbol = new HashMap<>();
        Map<String, BigDecimal> atrBySymbol = new HashMap<>();
        for (ExecutorPosition p : survivors) {
            ExecutorIndicators.Levels lv = indicators.levels(p.symbol(), atrPeriod, swingPeriod);
            if (!lv.available()) continue;
            if (lv.referencePrice() != null) closeBySymbol.put(p.symbol(), lv.referencePrice());
            if (lv.atr() != null) atrBySymbol.put(p.symbol(), lv.atr());
        }

        List<ExecutorPosition> afterHard = hardTrigger.apply(survivors, closeBySymbol, runId);
        ratchet.ratchet(afterHard, atrBySymbol, runId);

        Set<Long> afterHardIds = new HashSet<>();
        for (ExecutorPosition p : afterHard) afterHardIds.add(p.id());

        List<ExecutorPosition> finalOpen = positionRepo.findOpen().stream()
                .filter(p -> connection.equals(p.connection()))
                .filter(p -> afterHardIds.contains(p.id()))
                .toList();

        List<EnrichedPosition> enriched = new ArrayList<>();
        for (ExecutorPosition p : finalOpen) {
            enriched.add(enrich(p, closeBySymbol.get(p.symbol()), atrBySymbol.get(p.symbol())));
        }
        return enriched;
    }

    private EnrichedPosition enrich(ExecutorPosition p, BigDecimal currentPrice, BigDecimal atr) {
        boolean sell = "SELL".equals(p.side());

        BigDecimal chandelierLevel = null;
        if (currentPrice != null && atr != null && p.highestPrice() != null) {
            BigDecimal offset = atr.multiply(BigDecimal.valueOf(chandelierMult));
            chandelierLevel = sell ? p.highestPrice().add(offset) : p.highestPrice().subtract(offset);
        }

        BigDecimal rCurrent = computeR(p, currentPrice, sell);

        SoftConditionEvaluator.SoftState ss = softEval.evaluate(currentPrice, chandelierLevel,
                null, null, p.side(), p.softConfirmCount());

        positionRepo.updateMaintenance(p.id(), p.highestPrice(), p.mfeR(), ss.confirmCount(),
                p.activeStop(), null);

        return new EnrichedPosition(p.id(), p.connection(), p.symbol(), p.side(), p.qty(),
                p.entryPrice(), p.activeStop(), currentPrice, atr, chandelierLevel, rCurrent,
                p.mfeR(), daysHeld(p.entryDate()), p.killCriteria(), ss.chandelierBreach(),
                ss.maBreak(), ss.confirmCount());
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
