package de.visterion.dracul.outcome;

import de.visterion.dracul.executor.AnchorCandidate;
import de.visterion.dracul.executor.AnchorShadowRow;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * SP12: gives pre-V49 LLM SKIPs (and future Agora-gap signals) the two anchor inputs the
 * counterfactual walk needs, reconstructed from today's daily history, and continuously checks
 * the reconstruction rule against anchors persisted at emission (shadow check, read-only).
 * Called by {@link OutcomeBatchJob#run()} right before the counterfactual walk, so a
 * reconstructed signal is walked the same night.
 */
@Component
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class AnchorReconstructionStep {

    private static final Logger log = LoggerFactory.getLogger(AnchorReconstructionStep.class);

    static final Instant SHADOW_EMITTED_AFTER = Instant.parse("2026-09-17T04:00:00Z");
    static final int LOOKBACK_MARGIN_DAYS = 60;
    static final int MAX_LOOKBACK_DAYS = 400;
    static final int PERMANENT_EMPTY_AGE_DAYS = 30;
    static final String NO_HISTORY = "no OHLC history";
    private static final BigDecimal REL_TOLERANCE = new BigDecimal("0.02");
    private static final BigDecimal ABS_TOLERANCE = new BigDecimal("0.0001");

    public record Summary(int candidates, int reconstructed, int unreconstructable, int deferred,
            int raced, Map<String, Integer> reasons, int shadowN, int shadowDateMatch,
            int shadowAtrMatch, int shadowExpectedDivergence, int shadowMismatch, int shadowSkipped) {
        static Summary disabled() { return new Summary(0, 0, 0, 0, 0, Map.of(), 0, 0, 0, 0, 0, 0); }
    }

    private record Fetch(List<OhlcBar> bars, LocalDate windowStart, boolean failed) {}

    private final ExecutorSignalRepository signals;
    private final AgoraMarketData marketData;
    private final AnchorReconstructor reconstructor = new AnchorReconstructor();
    private final Clock clock;
    private final boolean enabled;
    private final int maxPerRun;
    private final int shadowSample;

    @Autowired
    public AnchorReconstructionStep(ExecutorSignalRepository signals, AgoraMarketData marketData,
            @Value("${dracul.outcome.reconstruct-anchors.enabled:true}") boolean enabled,
            @Value("${dracul.outcome.reconstruct-anchors.max-per-run:100}") int maxPerRun,
            @Value("${dracul.outcome.reconstruct-anchors.shadow-sample:10}") int shadowSample) {
        this(signals, marketData, enabled, maxPerRun, shadowSample, Clock.systemUTC());
    }

    AnchorReconstructionStep(ExecutorSignalRepository signals, AgoraMarketData marketData,
            boolean enabled, int maxPerRun, int shadowSample, Clock clock) {
        this.signals = signals;
        this.marketData = marketData;
        this.enabled = enabled;
        this.maxPerRun = maxPerRun;
        this.shadowSample = shadowSample;
        this.clock = clock;
    }

    public Summary run() {
        if (!enabled) return Summary.disabled();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<AnchorCandidate> candidates = signals.findAnchorCandidates(maxPerRun);
        List<AnchorShadowRow> shadow = shadowSample > 0
                ? signals.findShadowSample(shadowSample, SHADOW_EMITTED_AFTER) : List.of();

        // oldest emission per symbol decides the lookback for that symbol
        Map<String, Instant> oldest = new LinkedHashMap<>();
        for (AnchorCandidate c : candidates) oldest.merge(sym(c.symbol()), c.emittedAt(), AnchorReconstructionStep::min);
        for (AnchorShadowRow s : shadow) oldest.merge(sym(s.symbol()), s.emittedAt(), AnchorReconstructionStep::min);

        Map<String, Fetch> fetched = new HashMap<>();
        for (var e : oldest.entrySet()) fetched.put(e.getKey(), fetch(e.getKey(), e.getValue(), today));
        boolean anyHealthy = fetched.values().stream().anyMatch(f -> !f.failed() && !f.bars().isEmpty());
        boolean outage = !anyHealthy && fetched.values().stream().anyMatch(f -> !f.failed());

        int reconstructed = 0, unreconstructable = 0, deferred = 0, raced = 0;
        Map<String, Integer> reasons = new TreeMap<>();
        for (AnchorCandidate c : candidates) {
            try {
                Fetch f = fetched.get(sym(c.symbol()));
                if (f == null || f.failed() || outage) { deferred++; continue; }
                AnchorReconstructor.Result r = reconstructor.reconstruct(sym(c.symbol()), c.emittedAt(), f.bars(), f.windowStart());
                if (r instanceof AnchorReconstructor.Anchor a) {
                    if (signals.writeReconstructedAnchor(c.signalId(), a.barDate(), a.atr()) == 1) {
                        reconstructed++;
                    } else {
                        raced++;
                        log.warn("outcome batch: anchor reconstruction for signal {} ({}) updated no row "
                                + "(anchors or reference_source changed concurrently)", c.signalId(), c.symbol());
                    }
                    continue;
                }
                AnchorReconstructor.Failure fail = (AnchorReconstructor.Failure) r;
                String reason = fail.reason();
                boolean permanent = fail.permanent();
                if (!permanent && AnchorReconstructor.NO_DATA.equals(reason) && anyHealthy
                        && ChronoUnit.DAYS.between(c.emittedAt().atZone(ZoneOffset.UTC).toLocalDate(), today)
                           > PERMANENT_EMPTY_AGE_DAYS) {
                    permanent = true;
                    reason = NO_HISTORY;
                }
                reasons.merge(reason, 1, Integer::sum);
                if (permanent) {
                    if (signals.markUnreconstructable(c.signalId()) == 1) {
                        unreconstructable++;
                        log.info("outcome batch: signal {} ({}) not reconstructable: {}", c.signalId(), c.symbol(), reason);
                    } else {
                        raced++;
                        log.warn("outcome batch: markUnreconstructable for signal {} ({}) updated no row "
                                + "(reference_source changed concurrently)", c.signalId(), c.symbol());
                    }
                } else {
                    deferred++;
                }
            } catch (RuntimeException e) {
                // One bad candidate must never abort the rest of the batch or the shadow check
                // that runs after this loop.
                deferred++;
                log.warn("outcome batch: anchor reconstruction for signal {} ({}) threw: {}",
                        c.signalId(), c.symbol(), e.getMessage(), e);
            }
        }

        int dateMatch = 0, atrMatch = 0, expected = 0, mismatch = 0, skipped = 0;
        for (AnchorShadowRow s : shadow) {
            try {
                Fetch f = fetched.get(sym(s.symbol()));
                if (f == null || f.failed()) { skipped++; continue; }          // not comparable tonight
                if (f.bars().isEmpty()) {
                    // The source answered (no failure), but served nothing at all over the whole
                    // lookback -- not comparable either, and not the reconstruction rule's fault,
                    // so it must not count as a mismatch or trigger a rule-regression WARN.
                    skipped++;
                    log.debug("anchor shadow check: signal {} ({}) skipped — fetch served no bars "
                            + "over the whole lookback", s.signalId(), s.symbol());
                    continue;
                }
                AnchorReconstructor.Result r = reconstructor.reconstruct(sym(s.symbol()), s.emittedAt(), f.bars(), f.windowStart());
                if (r instanceof AnchorReconstructor.Failure fail) {
                    if (fail.permanent() && AnchorReconstructor.STALE.equals(fail.reason())) { expected++; continue; }
                    mismatch++;
                    log.warn("anchor shadow check: signal {} ({}) stored {} / {} but reconstruction failed: {}",
                            s.signalId(), s.symbol(), s.storedBarDate(), s.storedAtr(), fail.reason());
                    continue;
                }
                AnchorReconstructor.Anchor a = (AnchorReconstructor.Anchor) r;
                boolean dOk = a.barDate().equals(s.storedBarDate());
                boolean aOk = atrWithinTolerance(a.atr(), s.storedAtr());
                if (dOk) dateMatch++;
                if (aOk) atrMatch++;
                if (!dOk || !aOk) {
                    mismatch++;
                    log.warn("anchor shadow check: signal {} ({}) stored {} / {} reconstructed {} / {}",
                            s.signalId(), s.symbol(), s.storedBarDate(), s.storedAtr(), a.barDate(), a.atr());
                }
            } catch (RuntimeException e) {
                // One bad shadow row must never abort the rest of the shadow check.
                skipped++;
                log.warn("anchor shadow check: signal {} ({}) processing threw: {}",
                        s.signalId(), s.symbol(), e.getMessage(), e);
            }
        }
        int shadowN = shadow.size();
        if (shadowN > 0) {
            log.info("anchor shadow check: n={} dateMatch={} atrMatch={} expectedDivergence={} mismatch={} skipped={}",
                    shadowN, dateMatch, atrMatch, expected, mismatch, skipped);
            if (expected == shadowN) {
                log.warn("anchor shadow check: every sampled emission row diverged as 'expected' — "
                        + "the check compared nothing tonight");
            }
            if (skipped == shadowN) {
                log.warn("anchor shadow check: every sampled row was skipped — "
                        + "the check compared nothing tonight");
            }
        }
        Summary summary = new Summary(candidates.size(), reconstructed, unreconstructable, deferred,
                raced, reasons, shadowN, dateMatch, atrMatch, expected, mismatch, skipped);
        log.info("outcome batch: anchor reconstruction: candidates={} reconstructed={} "
                        + "unreconstructable={} deferred={} raced={}{} (reasons: {})",
                summary.candidates(), reconstructed, unreconstructable, deferred, raced,
                outage ? " OUTAGE" : "", reasons);
        return summary;
    }

    private Fetch fetch(String symbol, Instant oldestEmission, LocalDate today) {
        long age = ChronoUnit.DAYS.between(oldestEmission.atZone(ZoneOffset.UTC).toLocalDate(), today);
        int lookback = (int) Math.min(MAX_LOOKBACK_DAYS, Math.max(0, age) + LOOKBACK_MARGIN_DAYS);
        LocalDate windowStart = today.minusDays(lookback);
        try {
            return new Fetch(marketData.dailyOhlcHistory(symbol, lookback), windowStart, false);
        } catch (MarketDataException e) {
            log.warn("outcome batch: OHLC unavailable for {} (anchor reconstruction): {}", symbol, e.getMessage());
            return new Fetch(List.of(), windowStart, true);
        }
    }

    static boolean atrWithinTolerance(BigDecimal reconstructed, BigDecimal stored) {
        if (reconstructed == null || stored == null) return false;
        BigDecimal a = reconstructed.setScale(4, RoundingMode.HALF_UP);
        BigDecimal b = stored.setScale(4, RoundingMode.HALF_UP);
        BigDecimal diff = a.subtract(b).abs();
        BigDecimal rel = b.abs().multiply(REL_TOLERANCE);
        return diff.compareTo(rel.max(ABS_TOLERANCE)) <= 0;
    }

    private static String sym(String s) { return s == null ? "" : s.trim(); }

    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
