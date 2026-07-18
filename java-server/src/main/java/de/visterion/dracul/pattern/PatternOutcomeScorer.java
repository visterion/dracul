package de.visterion.dracul.pattern;

import de.visterion.dracul.hunting.agora.SectorCascade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Weekly pattern outcome scorer (spec T3.3 D5): matches every completed TRADE outcome
 * against every PENDING/ACTIVE gated pattern and writes idempotent {@code pattern_evidence}
 * rows plus the aggregate columns — the first runtime writer of the previously seed-only
 * evidence model. Full rescan every run, no watermark: the (pattern_id, outcome_ref)
 * dedupe index makes re-inserts no-ops, volume is tiny, and newly proposed PENDING gates
 * immediately receive historical evidence. Scheduled before the Saturday voievod-outcome
 * run. Patterns without a gate are skipped (nothing machine-checkable to match) — their
 * evidence stays LLM-curated.
 */
@Component
@ConditionalOnProperty(value = "dracul.pattern-scorer.enabled", havingValue = "true",
        matchIfMissing = true)
public class PatternOutcomeScorer {

    private static final Logger log = LoggerFactory.getLogger(PatternOutcomeScorer.class);

    private final JdbcClient jdbc;
    private final PatternRepository patterns;
    private final SectorCascade sectorCascade;

    public PatternOutcomeScorer(JdbcClient jdbc, PatternRepository patterns,
            SectorCascade sectorCascade) {
        this.jdbc = jdbc;
        this.patterns = patterns;
        this.sectorCascade = sectorCascade;
    }

    /** Read-model row: outcome_log (kind=TRADE, complete) joined to executor_position and
     *  (via source_signal_id) executor_signal — singular table names per V17. */
    record ScorableOutcome(String logIdRef, BigDecimal realizedR, String computedAt,
            String symbol, BigDecimal entryPrice, BigDecimal initialStop, String sector,
            String closedAt, String mechanism, Double confidence) {}

    private record ParsedGate(String patternId, GatePredicate predicate) {}

    @Scheduled(cron = "${dracul.pattern-scorer.cron:0 0 6 * * 6}")
    public void run() {
        try {
            score();
        } catch (Exception e) {
            log.error("pattern outcome scorer failed", e);
        }
    }

    void score() {
        List<ParsedGate> gates = parseGates(patterns.findGatedForScoring());
        if (gates.isEmpty()) {
            log.debug("pattern scorer: no gated PENDING/ACTIVE patterns — nothing to score");
            return;
        }
        List<ScorableOutcome> outcomes = fetchScorables();
        Set<String> touched = new LinkedHashSet<>();
        for (ScorableOutcome outcome : outcomes) {
            try {
                scoreOutcome(outcome, gates, touched);
            } catch (Exception e) {
                log.error("pattern scorer: outcome {} ({}) failed — continuing",
                        outcome.logIdRef(), outcome.symbol(), e);
            }
        }
        for (String patternId : touched) {
            patterns.recomputeAggregates(patternId);
        }
        log.info("pattern scorer: {} gated patterns x {} outcomes, {} patterns updated",
                gates.size(), outcomes.size(), touched.size());
    }

    private List<ParsedGate> parseGates(List<Pattern> gated) {
        List<ParsedGate> parsed = new ArrayList<>();
        for (Pattern p : gated) {
            GateValidator.Result result = GateValidator.validate(p.gateJson());
            if (!result.valid()) {
                log.warn("pattern scorer: pattern {} has invalid stored gate — skipped: {}",
                        p.id(), result.errors());
                continue;
            }
            parsed.add(new ParsedGate(p.id(), result.predicate()));
        }
        return parsed;
    }

    private List<ScorableOutcome> fetchScorables() {
        return jdbc.sql("""
                SELECT o.log_id_ref, o.realized_r, o.computed_at,
                       p.symbol, p.entry_price, p.initial_stop, p.sector, p.closed_at,
                       s.mechanism, s.confidence
                FROM outcome_log o
                JOIN executor_position p ON p.id = o.position_id
                LEFT JOIN executor_signal s ON s.signal_id = p.source_signal_id
                WHERE o.kind = 'TRADE' AND o.complete = true
                ORDER BY o.computed_at
                """)
                .query((rs, n) -> new ScorableOutcome(
                        rs.getString("log_id_ref"),
                        rs.getBigDecimal("realized_r"),
                        rs.getString("computed_at"),
                        rs.getString("symbol"),
                        rs.getBigDecimal("entry_price"),
                        rs.getBigDecimal("initial_stop"),
                        rs.getString("sector"),
                        rs.getString("closed_at"),
                        rs.getString("mechanism"),
                        rs.getObject("confidence") == null ? null
                                : ((Number) rs.getObject("confidence")).doubleValue()))
                .list();
    }

    private void scoreOutcome(ScorableOutcome o, List<ParsedGate> gates, Set<String> touched) {
        // Defined null-skip (not an error): structurally null rows must not poison every
        // weekly rescan. Same r_per_share basis as OutcomeBatchJob: |entry - initial_stop|.
        if (o.realizedR() == null || o.entryPrice() == null || o.initialStop() == null
                || o.entryPrice().signum() == 0) {
            log.debug("pattern scorer: outcome {} skipped (null realized_r/entry/stop)",
                    o.logIdRef());
            return;
        }
        BigDecimal rPerShare = o.entryPrice().subtract(o.initialStop()).abs();
        if (rPerShare.signum() == 0) {
            log.debug("pattern scorer: outcome {} skipped (zero r_per_share)", o.logIdRef());
            return;
        }
        BigDecimal returnPercent = o.realizedR()
                .multiply(rPerShare)
                .divide(o.entryPrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        String sector = o.sector() != null ? o.sector() : sectorCascade.resolve(o.symbol());
        GateSignalView view = new GateSignalView(o.mechanism(), o.symbol(), sector,
                o.confidence(), o.entryPrice());

        String occurredAt = o.closedAt() != null ? o.closedAt() : o.computedAt();
        boolean supported = returnPercent.signum() < 0;

        for (ParsedGate gate : gates) {
            var match = GateEvaluator.evaluate(gate.predicate(), view);
            if (match.isEmpty()) {
                log.warn("pattern scorer: gate {} not evaluable for outcome {} — fail-open",
                        gate.patternId(), o.logIdRef());
                continue;
            }
            if (!match.get().matched()) continue;
            boolean inserted = patterns.insertAutoEvidence(gate.patternId(), o.symbol(),
                    o.symbol(), o.mechanism() != null ? o.mechanism() : "UNKNOWN",
                    occurredAt, supported, returnPercent, o.logIdRef());
            if (inserted) touched.add(gate.patternId());
        }
    }
}
