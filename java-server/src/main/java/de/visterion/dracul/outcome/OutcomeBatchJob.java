package de.visterion.dracul.outcome;

import de.visterion.dracul.executor.DecisionLog;
import de.visterion.dracul.executor.DecisionLogRepository;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignal;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.voievod.Horizons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Deterministic, code-only nightly batch: fills {@code outcome_log} with (a) realized outcomes
 * of closed trades and (b) counterfactual "what would have happened" records for every rejected
 * entry signal. No LLM calls. The Executor never reads or writes this table — it exists purely
 * for offline hunter-confidence calibration and post-mortem analysis.
 *
 * <p>Runs after the executor's evening cycle by default ({@code dracul.outcome.cron}, UTC).
 * A batch failure must never break the app: the whole run and every per-item step are wrapped so
 * one bad symbol/position never aborts the rest of the batch.
 */
@Component
@ConditionalOnProperty(value = "dracul.outcome.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class OutcomeBatchJob {

    private static final Logger log = LoggerFactory.getLogger(OutcomeBatchJob.class);

    /** Exit actions that close a position for good (a partial TRIM is not one of these). */
    private static final List<String> EXIT_ACTIONS = List.of("EXIT_FULL", "LOG_HARD_EXIT", "RECONCILE_CLOSE");
    private static final List<String> TRIM_ACTIONS = List.of("TRIM");
    private static final List<String> ENTER_ACTIONS = List.of("ENTER");

    private static final int DEFAULT_HORIZON_TRADING_DAYS = 60;
    private static final int MAX_OHLC_LOOKBACK_DAYS = 400;
    private static final int REENTRY_WINDOW_CALENDAR_DAYS = 14; // approximates 10 trading days

    private final ExecutorPositionRepository positions;
    private final DecisionLogRepository decisionLog;
    private final ExecutorSignalRepository signals;
    private final OutcomeLogRepository outcomeLog;
    private final HypotheticalREngine engine;
    private final AgoraMarketData marketData;
    private final ObjectMapper mapper;

    public OutcomeBatchJob(ExecutorPositionRepository positions, DecisionLogRepository decisionLog,
            ExecutorSignalRepository signals, OutcomeLogRepository outcomeLog,
            HypotheticalREngine engine, AgoraMarketData marketData, ObjectMapper mapper) {
        this.positions = positions;
        this.decisionLog = decisionLog;
        this.signals = signals;
        this.outcomeLog = outcomeLog;
        this.engine = engine;
        this.marketData = marketData;
        this.mapper = mapper;
    }

    @Scheduled(cron = "${dracul.outcome.cron:0 30 22 * * 2-6}")
    public void run() {
        try {
            processTrades();
            processCounterfactuals();
        } catch (Exception e) {
            log.error("outcome batch job failed", e);
        }
    }

    // -------------------------------------------------------------------
    // TRADE — realized outcomes of closed positions
    // -------------------------------------------------------------------

    private void processTrades() {
        for (ExecutorPosition p : positions.findClosed()) {
            try {
                processClosedPosition(p);
            } catch (Exception e) {
                log.warn("outcome batch: TRADE processing failed for position {} ({}): {}",
                        p.id(), p.symbol(), e.getMessage(), e);
            }
        }
    }

    private void processClosedPosition(ExecutorPosition p) {
        DecisionLog enter = resolveEnterDecision(p);
        if (enter == null) {
            log.warn("outcome batch: no ENTER decision_log row found for closed position {} ({}); "
                    + "skipping (no fabricated join)", p.id(), p.symbol());
            return;
        }
        String logIdRef = enter.logId();
        // A TRADE row is only final once the re-entry window has elapsed: reentry_within_10d
        // observes ENTER rows written on days 1..14 AFTER the close, so marking the row complete
        // on the first run after close would freeze the flag at false forever. Until
        // (closedAt + REENTRY_WINDOW_CALENDAR_DAYS) has passed, re-runs recompute the whipsaw
        // flags via the idempotent upsert; afterwards the row flips complete and is skipped.
        if (outcomeLog.isComplete(logIdRef)) return;

        BigDecimal entryPrice = p.entryPrice();
        BigDecimal initialStop = p.initialStop();
        BigDecimal rPerShare = (entryPrice != null && initialStop != null)
                ? entryPrice.subtract(initialStop).abs() : null;

        LocalDate entryDate = parseLocalDate(p.entryDate());
        LocalDate closedDate = parseLocalDate(p.closedAt());
        // Widen to whole calendar days either side since entryDate/closedAt are local-timezone-
        // rendered dates (Timestamp.toString) but decision_log.created_at is compared as an
        // absolute UTC instant. A run whose local date is a day ahead of a leg's UTC instant
        // (e.g. the 22:30 UTC cron firing at 00:30 in a UTC+2 deployment) would otherwise place
        // windowFrom in the future relative to that leg and silently drop it — collapsing a
        // trimmed position's quantity-weighted R to the final leg only. The ±1-day pad absorbs
        // that skew; per-position TRIM/exit linkage stays exact via ownedByPosition(position_id).
        Instant windowFrom = entryDate != null
                ? entryDate.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : null;
        Instant windowTo = closedDate != null
                ? closedDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.now();

        // TRIM/exit rows carry order_json.position_id (exact linkage). Rows stamped with a
        // DIFFERENT position's id are always excluded — that is what keeps two same-day
        // lifecycles on one symbol apart. Rows WITHOUT a position_id (pre-linkage historical
        // rows) fall back to the symbol+calendar-day window heuristic the query already applied.
        List<DecisionLog> trims = ownedByPosition(windowFrom != null
                ? decisionLog.findBySymbolAndActionsBetween(p.symbol(), TRIM_ACTIONS, windowFrom, windowTo)
                : List.of(), p.id());
        List<DecisionLog> exits = ownedByPosition(windowFrom != null
                ? decisionLog.findBySymbolAndActionsBetween(p.symbol(), EXIT_ACTIONS, windowFrom, windowTo)
                : List.of(), p.id());
        DecisionLog finalExit = exits.isEmpty() ? null : exits.get(exits.size() - 1);

        WeightedR weighted = weightedRealizedR(p, trims, rPerShare);

        BigDecimal maeR = maeR(p, rPerShare);
        BigDecimal limitPrice = bigDecimalOrNull(enter.orderJson(), "limit_price");
        BigDecimal slippage = (limitPrice != null && entryPrice != null)
                ? entryPrice.subtract(limitPrice) : null;

        Integer holdingDays = (entryDate != null && closedDate != null)
                ? (int) ChronoUnit.DAYS.between(entryDate, closedDate) : null;

        // Day-level granularity (per the documented calendar-day approximation) means a same-day
        // close+reentry window can't be told apart from the position's own ENTER row by time
        // alone -> explicitly exclude logIdRef itself rather than relying on the window start.
        boolean reentry = closedDate != null && decisionLog.findBySymbolAndActionsBetween(
                p.symbol(), ENTER_ACTIONS,
                closedDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
                closedDate.plusDays(REENTRY_WINDOW_CALENDAR_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant())
                .stream().anyMatch(d -> !logIdRef.equals(d.logId()));

        String exitTrigger = finalExit != null ? finalExit.reasonCode() : p.exitReason();
        String exitLogId = finalExit != null ? finalExit.logId() : null;
        if (finalExit == null) {
            log.warn("outcome batch: no exit decision_log row found for closed position {} ({}); "
                    + "falling back to the position's own exit_reason", p.id(), p.symbol());
        }

        // Complete only once the documented re-entry window (14 calendar days ≈ 10 trading days)
        // after the close has fully passed — see the isComplete note above. An unparseable
        // closedAt can never resolve the window, so it completes immediately (old behavior).
        boolean reentryWindowElapsed = closedDate == null
                || !LocalDate.now(ZoneOffset.UTC)
                        .isBefore(closedDate.plusDays(REENTRY_WINDOW_CALENDAR_DAYS));

        OutcomeLogRow row = new OutcomeLogRow(
                "TRADE", logIdRef, p.id(), p.symbol(), null,
                true, entryPrice, slippage, holdingDays,
                p.mfeR(), maeR, weighted.realizedR(),
                exitTrigger, exitLogId, weighted.partialExits(),
                reentry, holdingDays != null && holdingDays < 5,
                null, null,
                enter.sourceAgent(), enter.sourceAgentVersion(), enter.ruleVersion(),
                reentryWindowElapsed);
        outcomeLog.upsert(row);
    }

    private DecisionLog resolveEnterDecision(ExecutorPosition p) {
        if (p.sourceSignalId() != null) {
            DecisionLog bySignal = decisionLog.findBySignalIdAndAction(p.sourceSignalId(), "ENTER");
            if (bySignal != null) return bySignal;
        }
        List<DecisionLog> candidates = decisionLog.findBySymbolAndAction(p.symbol(), "ENTER");
        if (candidates.isEmpty()) return null;
        LocalDate entryDate = parseLocalDate(p.entryDate());
        if (entryDate == null) return candidates.get(candidates.size() - 1);

        DecisionLog best = null;
        long bestDiff = Long.MAX_VALUE;
        for (DecisionLog c : candidates) {
            LocalDate cd = parseLocalDate(c.createdAt());
            if (cd == null) continue;
            long diff = Math.abs(ChronoUnit.DAYS.between(entryDate, cd));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = c;
            }
        }
        return best != null ? best : candidates.get(candidates.size() - 1);
    }

    /** Keeps rows linked to {@code positionId} via {@code order_json.position_id}, plus rows
     *  with no linkage at all (historical rows written before position_id stamping existed,
     *  which rely on the caller's symbol+window heuristic). Rows explicitly stamped with a
     *  different position's id are always dropped. */
    private static List<DecisionLog> ownedByPosition(List<DecisionLog> rows, Long positionId) {
        return rows.stream().filter(d -> {
            JsonNode pid = d.orderJson() == null ? null : d.orderJson().path("position_id");
            if (pid == null || pid.isMissingNode() || pid.isNull()) return true; // pre-linkage row
            return pid.canConvertToLong() && positionId != null && pid.asLong() == positionId;
        }).toList();
    }

    private record WeightedR(BigDecimal realizedR, ArrayNode partialExits) {}

    /** Quantity-weighted realized R across every TRIM leg plus the final exit leg. Falls back to
     *  the position's own {@code realized_r} (the final leg only) when any leg's quantity/price
     *  is missing — never fabricates a weighted figure from incomplete data. */
    private WeightedR weightedRealizedR(ExecutorPosition p, List<DecisionLog> trims, BigDecimal rPerShare) {
        ArrayNode partialExits = mapper.createArrayNode();
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal totalQty = BigDecimal.ZERO;
        boolean computable = rPerShare != null && rPerShare.signum() != 0;

        for (DecisionLog t : trims) {
            JsonNode oj = t.orderJson();
            BigDecimal qtyClosed = bigDecimalOrNull(oj, "qty_closed");
            BigDecimal price = bigDecimalOrNull(oj, "price");
            Double fraction = (oj != null && oj.path("fraction").isNumber()) ? oj.path("fraction").asDouble() : null;

            ObjectNode pe = mapper.createObjectNode();
            if (fraction != null) pe.put("fraction", fraction); else pe.putNull("fraction");
            if (price != null) pe.put("price", price); else pe.putNull("price");
            pe.put("trigger", "TRIM");
            pe.put("log_id", t.logId());
            partialExits.add(pe);

            if (!computable) continue;
            if (qtyClosed == null || price == null) {
                computable = false;
                continue;
            }
            BigDecimal r = computeR(p, price, rPerShare);
            weightedSum = weightedSum.add(qtyClosed.multiply(r));
            totalQty = totalQty.add(qtyClosed);
        }

        BigDecimal finalQty = p.qty();
        BigDecimal finalPrice = p.exitPrice();
        if (computable && finalQty != null && finalPrice != null) {
            BigDecimal r = computeR(p, finalPrice, rPerShare);
            weightedSum = weightedSum.add(finalQty.multiply(r));
            totalQty = totalQty.add(finalQty);
        } else {
            computable = false;
        }

        BigDecimal realizedR = (computable && totalQty.signum() > 0)
                ? weightedSum.divide(totalQty, 4, RoundingMode.HALF_UP)
                : p.realizedR();
        return new WeightedR(realizedR, partialExits);
    }

    /** Side-aware R of {@code price} against the position's entry, mirroring
     *  {@code ExecutorWebhookController.computeR}'s BUY/SELL formula. */
    private BigDecimal computeR(ExecutorPosition p, BigDecimal price, BigDecimal rPerShare) {
        BigDecimal delta = "SELL".equals(p.side())
                ? p.entryPrice().subtract(price)
                : price.subtract(p.entryPrice());
        return delta.divide(rPerShare, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal maeR(ExecutorPosition p, BigDecimal rPerShare) {
        if (rPerShare == null || rPerShare.signum() == 0 || p.entryPrice() == null) return null;
        if ("SELL".equals(p.side())) {
            if (p.highestPrice() == null) return null;
            return p.entryPrice().subtract(p.highestPrice()).divide(rPerShare, 4, RoundingMode.HALF_UP);
        }
        if (p.lowestPrice() == null) return null;
        return p.lowestPrice().subtract(p.entryPrice()).divide(rPerShare, 4, RoundingMode.HALF_UP);
    }

    // -------------------------------------------------------------------
    // COUNTERFACTUAL — "what if we had taken this rejected signal anyway"
    // -------------------------------------------------------------------

    private void processCounterfactuals() {
        for (DecisionLog reject : decisionLog.findSignalRowsByAction("REJECT")) {
            try {
                processReject(reject);
            } catch (Exception e) {
                log.warn("outcome batch: COUNTERFACTUAL processing failed for {} ({}): {}",
                        reject.logId(), reject.symbol(), e.getMessage(), e);
            }
        }
    }

    private void processReject(DecisionLog reject) {
        String logIdRef = reject.logId();
        if (outcomeLog.isComplete(logIdRef)) return; // already finished (skipped or 60-bar window filled)

        JsonNode snap = reject.inputsSnapshot();
        BigDecimal orderPrice = bigDecimalOrNull(snap, "order_price");
        BigDecimal atr = bigDecimalOrNull(snap, "atr");

        ExecutorSignal signal = reject.signalId() != null ? signals.findById(reject.signalId()) : null;
        String side = signal != null ? signal.direction() : null;

        HypotheticalOutcome outcome;
        boolean complete;

        if (orderPrice == null || atr == null || side == null) {
            String reason = side == null
                    ? "signal direction unresolvable (no signal_id match)"
                    : "missing order_price/atr in inputs_snapshot";
            outcome = HypotheticalOutcome.skipped(reason);
            complete = true;
        } else {
            LocalDate decisionDate = parseLocalDate(reject.createdAt());
            if (decisionDate == null) {
                outcome = HypotheticalOutcome.skipped("decision_log created_at unparseable");
                complete = true;
            } else {
                List<OhlcBar> bars;
                try {
                    bars = fetchBarsAfter(reject.symbol(), decisionDate);
                } catch (MarketDataException e) {
                    // Transient data-provider outage, not a permanent skip: leave the row
                    // untouched (absent/incomplete) so the next nightly run retries.
                    log.warn("outcome batch: OHLC unavailable for {} (reject {}): {}",
                            reject.symbol(), logIdRef, e.getMessage());
                    return;
                }
                int horizon = resolveHorizon(signal);
                outcome = engine.walk(side, orderPrice, atr, null, bars, horizon);
                complete = outcome.skippedReason() != null || bars.size() >= 60;
            }
        }

        ObjectNode hypo = mapper.createObjectNode();
        putOrNull(hypo, "r_after_20d", outcome.rAfter20d());
        putOrNull(hypo, "r_after_60d", outcome.rAfter60d());
        hypo.put("would_have_stopped_out", outcome.wouldHaveStoppedOut());
        if (outcome.skippedReason() != null) hypo.put("skipped_reason", outcome.skippedReason());
        else hypo.putNull("skipped_reason");

        OutcomeLogRow row = new OutcomeLogRow(
                "COUNTERFACTUAL", logIdRef, null, reject.symbol(), reject.reasonCode(),
                null, null, null, null,
                null, null, null,
                null, null, null,
                null, null,
                hypo, outcome.tripleBarrierLabel(),
                reject.sourceAgent(), reject.sourceAgentVersion(), reject.ruleVersion(),
                complete);
        outcomeLog.upsert(row);
    }

    private List<OhlcBar> fetchBarsAfter(String symbol, LocalDate signalDate) {
        long daysSince = ChronoUnit.DAYS.between(signalDate, LocalDate.now(ZoneOffset.UTC));
        int lookback = (int) Math.min(MAX_OHLC_LOOKBACK_DAYS, Math.max(90, daysSince + 90));
        List<OhlcBar> all = marketData.dailyOhlcHistory(symbol, lookback);
        return all.stream().filter(b -> b.date().isAfter(signalDate)).toList();
    }

    private int resolveHorizon(ExecutorSignal signal) {
        if (signal == null || signal.horizon() == null) return DEFAULT_HORIZON_TRADING_DAYS;
        int approxCalendarDays = Horizons.approxDays(signal.horizon());
        if (approxCalendarDays <= 0) return DEFAULT_HORIZON_TRADING_DAYS;
        int tradingDays = (int) Math.round(approxCalendarDays * 5.0 / 7.0);
        return tradingDays > 0 ? tradingDays : DEFAULT_HORIZON_TRADING_DAYS;
    }

    // -------------------------------------------------------------------
    // shared helpers
    // -------------------------------------------------------------------

    private static LocalDate parseLocalDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal bigDecimalOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        try {
            return new BigDecimal(v.asString());
        } catch (Exception e) {
            return null;
        }
    }

    private static void putOrNull(ObjectNode n, String field, BigDecimal v) {
        if (v != null) n.put(field, v); else n.putNull(field);
    }
}
