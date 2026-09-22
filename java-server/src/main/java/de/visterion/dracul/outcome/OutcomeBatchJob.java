package de.visterion.dracul.outcome;

import de.visterion.dracul.executor.DecisionLog;
import de.visterion.dracul.executor.DecisionLogRepository;
import de.visterion.dracul.executor.ExecutorDecision;
import de.visterion.dracul.executor.ExecutorDecisionRepository;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignal;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.executor.RuleVersionProvider;
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
    private final ExecutorDecisionRepository executorDecisions;
    private final RuleVersionProvider ruleVersions;

    /** Per-run tally of counterfactuals whose symbol served no OHLC data at all. Only ever
     *  touched from the single-threaded {@code @Scheduled} run. */
    private int noDataSymbols;

    public OutcomeBatchJob(ExecutorPositionRepository positions, DecisionLogRepository decisionLog,
            ExecutorSignalRepository signals, OutcomeLogRepository outcomeLog,
            HypotheticalREngine engine, AgoraMarketData marketData, ObjectMapper mapper,
            ExecutorDecisionRepository executorDecisions, RuleVersionProvider ruleVersions) {
        this.positions = positions;
        this.decisionLog = decisionLog;
        this.signals = signals;
        this.outcomeLog = outcomeLog;
        this.engine = engine;
        this.marketData = marketData;
        this.mapper = mapper;
        this.executorDecisions = executorDecisions;
        this.ruleVersions = ruleVersions;
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
        noDataSymbols = 0;
        int seen = 0;
        for (DecisionLog reject : decisionLog.findSignalRowsByAction("REJECT")) {
            seen++;
            try {
                processReject(reject);
            } catch (Exception e) {
                log.warn("outcome batch: COUNTERFACTUAL processing failed for {} ({}): {}",
                        reject.logId(), reject.symbol(), e.getMessage(), e);
            }
        }
        // LLM SKIPs without a place_entry, processed AFTER the decision_log REJECT rows and with
        // the same isComplete(logIdRef) skip. They exist because a bare submit_decision SKIP
        // writes only executor_decision -- 202 of 242 SKIPPED signals were outside the learning
        // loop. The finder's NOT EXISTS already resolved the overlap: when place_entry vetoed the
        // signal in the same run, the veto reason wins and the SKIP is not counted again.
        for (ExecutorDecision skip : executorDecisions.findSkipsWithoutDecisionLog()) {
            seen++;
            try {
                processSignalAnchored(skip, "skip:" + skip.signalId(), "LLM_SKIP");
            } catch (Exception e) {
                log.warn("outcome batch: LLM_SKIP counterfactual failed for signal {} ({}): {}",
                        skip.signalId(), skip.symbol(), e.getMessage(), e);
            }
        }
        // Signals the PendingSignalSweeper retired without anyone evaluating them. Their own
        // reason code, NOT SIGNAL_EXPIRED: processReject anchors on the decision day, this walk on
        // the emission bar, and for a swept signal those are apart by construction (at least
        // max-signal-age-days + 1 trading days, unbounded above). Pooling the two would silently
        // redefine today's SIGNAL_EXPIRED datum.
        for (ExecutorDecision swept : executorDecisions.findSweptWithoutDecisionLog()) {
            seen++;
            try {
                processSignalAnchored(swept, "expired:" + swept.signalId(),
                        "SIGNAL_EXPIRED_UNEVALUATED");
            } catch (Exception e) {
                log.warn("outcome batch: SIGNAL_EXPIRED_UNEVALUATED counterfactual failed for "
                        + "signal {} ({}): {}", swept.signalId(), swept.symbol(), e.getMessage(), e);
            }
        }
        // Countable loss: without this line a batch in which every symbol came back empty looks
        // exactly like a clean batch. The same figure is visible to the operator in the
        // calibration report, where these rows are counted as `skipped` per reason_code.
        if (noDataSymbols > 0) {
            log.warn("outcome batch: {} of {} counterfactual(s) had no OHLC data at all "
                    + "and were written as skipped, not evaluated", noDataSymbols, seen);
        }
    }

    private void processReject(DecisionLog reject) {
        String logIdRef = reject.logId();
        if (outcomeLog.isComplete(logIdRef)) return; // already finished (skipped or 60-bar window filled)

        JsonNode snap = reject.inputsSnapshot();
        BigDecimal orderPrice = bigDecimalOrNull(snap, "order_price");
        BigDecimal atr = bigDecimalOrNull(snap, "atr");

        ExecutorSignal signal = reject.signalId() != null ? signals.findById(reject.signalId()) : null;
        // No ACCEPTED guard here (deliberately, unlike processSignalAnchored): findVetoRows'
        // read-side filter already excludes ACCEPTED-signal rows from veto_precision, but
        // findHunterBrierPoints needs exactly these REJECT counterfactual rows — TRADE rows carry
        // no hunter_label, so a REJECT row of a later-entered signal is the hunter Brier's only
        // source for that signal. Skipping the walk here would silently stop the hunter Brier
        // population from growing for every signal that took a transient/BROKER_ERROR reject and
        // was later entered (see documentation/api.md and OutcomeLogRepository for the split).
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
                BarsAfter fetched;
                try {
                    fetched = fetchBarsAfter(reject.symbol(), decisionDate);
                } catch (MarketDataException e) {
                    // Transient data-provider outage, not a permanent skip: leave the row
                    // untouched (absent/incomplete) so the next nightly run retries.
                    log.warn("outcome batch: OHLC unavailable for {} (reject {}): {}",
                            reject.symbol(), logIdRef, e.getMessage());
                    return;
                }
                if (fetched.sourceEmpty()) {
                    // The source served NO bars at all over the whole lookback. Since Agora
                    // stopped collapsing "this instrument has nothing to serve" into an error
                    // envelope, this arrives as a normal available:false payload rather than a
                    // MarketDataException -- so without this branch the walk would run over an
                    // empty path and write would_have_stopped_out=false, an answer to a question
                    // that was never evaluated. Reuse the engine's own missing-input path
                    // (HypotheticalOutcome.skipped): CalibrationService already excludes
                    // skipped rows from every veto mean and counts them separately.
                    outcome = HypotheticalOutcome.skipped(
                            "no OHLC bars available for symbol over the whole lookback");
                    // NOT complete: a data blackout is not a verdict on the signal. Leaving the
                    // row open means the next nightly run recomputes it for real once bars
                    // return, exactly like the MarketDataException branch above.
                    complete = false;
                    noDataSymbols++;
                    log.warn("outcome batch: no OHLC bars at all for {} (reject {}) — writing an "
                            + "explicitly skipped counterfactual, not a stopped-out=false verdict",
                            reject.symbol(), logIdRef);
                } else {
                    int horizon = resolveHorizon(signal);
                    List<OhlcBar> bars = fetched.after();
                    outcome = engine.walk(side, orderPrice, atr, null, bars, horizon);
                    // A row is final only once BOTH the 60-bar R figures and the label horizon are filled:
                    // tripleBarrierLabel answers "neither barrier hit" (false) only at bars >= horizon, and a row
                    // completed earlier would freeze a null label forever (isComplete early return).
                    complete = outcome.skippedReason() != null || bars.size() >= Math.max(60, horizon);
                }
            }
        }

        ObjectNode hypo = mapper.createObjectNode();
        putOrNull(hypo, "r_after_20d", outcome.rAfter20d());
        putOrNull(hypo, "r_after_60d", outcome.rAfter60d());
        // A skipped walk evaluated nothing, so its wouldHaveStoppedOut=false is a placeholder,
        // not a finding (see HypotheticalOutcome's class doc). Persisting that literal false
        // would let "we could not look" read as "the stop was never hit" — and findVetoRows()
        // reads this column with NO complete-filter, so it would land in the calibration report
        // immediately. JSON null is the honest value; parseBoolean(null) -> null, and
        // CalibrationService already divides only by the rows where it is non-null.
        if (outcome.skippedReason() != null) hypo.putNull("would_have_stopped_out");
        else hypo.put("would_have_stopped_out", outcome.wouldHaveStoppedOut());
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

    /**
     * Counterfactual for a signal that has no {@code decision_log} row of its own — the LLM's
     * outright SKIP ({@code LLM_SKIP}) and the sweeper's unevaluated expiry
     * ({@code SIGNAL_EXPIRED_UNEVALUATED}). Structurally the same walk as {@link #processReject},
     * with four deliberate differences:
     *
     * <ol>
     *   <li><b>The inputs come from the SIGNAL, not from an inputs_snapshot</b> — neither of these
     *       populations has a decision_log row at all. {@code reference_atr} is the ATR22 window,
     *       the same definition the REJECT path stores as {@code inputs_snapshot.atr}.</li>
     *   <li><b>The anchor is EMISSION, not the decision.</b> The REJECT counterfactual anchors on
     *       the day place_entry recomputed price and ATR; this one anchors on the bar the persisted
     *       price actually belongs to. For a decision taken on the emission day — 186 of 202 in the
     *       current {@code LLM_SKIP} population — that is the same day; the rest are anchored
     *       earlier than their verdict, which is stated in documentation/api.md rather than papered
     *       over. That statistic does not apply to the swept population sharing this method: by
     *       construction a swept signal's decision (if any) trails its emission by at least
     *       {@code max-signal-age-days + 1} trading days, so anchor and verdict are never the same
     *       day there.</li>
     *   <li><b>{@code log_id_ref} keys on the signal</b> ({@code "skip:" + signal_id} or
     *       {@code "expired:" + signal_id}), so a re-sent submit_decision, or a re-run of the
     *       sweep's finder, upserts this row instead of adding one.</li>
     *   <li><b>The ACCEPTED guard lives here, not in {@link #processReject}.</b> Both walks would
     *       otherwise duplicate a real trade's outcome next to a hypothetical one, but only here is
     *       it safe to skip the write outright: this population feeds no metric the batch owes an
     *       ACCEPTED-signal row for. {@code processReject}'s REJECT rows are different — they are
     *       {@code findHunterBrierPoints}' only source for an entered signal's hunter-Brier
     *       contribution (TRADE rows carry no {@code hunter_label}), so that guard was removed
     *       there; {@code findVetoRows}' read-side filter alone keeps such rows out of
     *       {@code veto_precision}. On the {@code LLM_SKIP} path this guard is unreachable today —
     *       a SKIP row's signal is {@code SKIPPED} in the same block, and {@code place_entry} on a
     *       non-PENDING signal answers {@code DUPLICATE} — but the swept path can genuinely race a
     *       later {@code place_entry} that books the position, so the check still has to run for
     *       both callers of this method.</li>
     * </ol>
     *
     * <p><b>Entry convention (SP8).</b> The walk enters at the open of the first bar after
     * {@code reference_bar_date} — the first price reachable after the emission — and records it
     * as {@code hypothetical.entry_source = "next_bar_open"} with {@code entry_price}. A signal
     * too fresh to have such a bar is walked with {@code reference_price} under
     * {@code entry_source = "reference_price"} and re-walked the next night. {@code
     * reference_price} itself is unchanged in meaning: it is the live print at emission, which is
     * what the drift vetoes and the LLM signal context consume. The 20-day
     * datum is therefore "open of the first walked bar -> close of the 20th walked bar", a clean
     * 20-session hold. {@link #processReject} keeps its decision-day anchor on purpose.
     *
     * <p>One limitation is shared with the REJECT path and not fixed here (§9 of the spec):
     * neither path checks the walked series against the stored price, so a split between emission
     * and walk yields a wrong R-multiple with {@code skipped = 0}. The partial-bar
     * {@code reference_atr} that used to be the other one is gone: once Agora's completed-bar
     * guard (SP8) is deployed, indicator values come from completed bars only; until then, or
     * after an Agora rollback, the partial-bar {@code reference_atr} limitation is still live.
     *
     * <p>The first bar's open is guarded against a non-positive value (a provider that emits
     * {@code BigDecimal.ZERO} for a missing open): a zero or negative open falls back to
     * {@code reference_price} under {@code entry_source = "reference_price"} instead of walking
     * from a garbage entry, exactly as the "no bar yet" branch above does.
     */
    private void processSignalAnchored(ExecutorDecision d, String logIdRef, String reasonCode) {
        if (outcomeLog.isComplete(logIdRef)) return;

        ExecutorSignal signal = signals.findById(d.signalId());
        // A signal that reached ACCEPTED has an executor_position, and its "what if" is answered
        // by the trade. Returning here writes nothing and marks nothing complete, so if the status
        // is ever corrected the next batch walks it for real. Null-tolerant on purpose: an
        // unresolvable signal falls through to the side == null branch below, exactly as before.
        if (signal != null && "ACCEPTED".equals(signal.status())) return;

        // 2026-09-11 prod defect: a crossed signal_id/symbol pair in a submitted SKIP persisted
        // the wrong instrument's symbol on the decision row. Walk the SIGNAL's symbol instead --
        // the row heals on the next batch even if the stored decision row is still wrong. Trimmed
        // symmetrically with the webhook's cross-check; a blank signal symbol is treated like a
        // missing one and falls back to the decision row's symbol.
        String signalSymbol = signal != null && signal.symbol() != null
                ? signal.symbol().trim() : "";
        String decisionSymbol = d.symbol() != null ? d.symbol().trim() : "";
        String symbol = !signalSymbol.isEmpty() ? signalSymbol : d.symbol();
        if (!signalSymbol.isEmpty() && !signalSymbol.equals(decisionSymbol)) {
            log.warn("outcome batch: decision row for signal {} names symbol '{}' but the signal "
                            + "is '{}' — walking the signal's symbol",
                    d.signalId(), d.symbol(), signal.symbol());
        }

        String side = signal != null ? signal.direction() : null;
        BigDecimal referencePrice = signal != null ? signal.referencePrice() : null;
        BigDecimal referenceAtr = signal != null ? signal.referenceAtr() : null;
        LocalDate anchor = signal != null ? signal.referenceBarDate() : null;

        HypotheticalOutcome outcome;
        boolean complete;
        // Which price the counterfactual was entered at, and where it came from. Both stay null
        // on every branch that returns before engine.walk -- a row that chose no entry must not
        // claim one -- and both are written on every branch that reaches it, a walk that itself
        // returns skipped included.
        String entrySource = null;
        BigDecimal entryPrice = null;

        if (side == null) {
            outcome = HypotheticalOutcome.skipped("signal direction unresolvable (no signal_id match)");
            complete = true;
        } else if (referencePrice == null || referencePrice.signum() <= 0
                || referenceAtr == null || anchor == null) {
            outcome = HypotheticalOutcome.skipped("missing reference_price/reference_atr/reference_bar_date");
            complete = true;
        } else {
            BarsAfter fetched;
            try {
                fetched = fetchBarsAfter(symbol, anchor);
            } catch (MarketDataException e) {
                // Transient provider outage, not a permanent skip: leave the row untouched so the
                // next nightly run retries. Same contract as processReject.
                log.warn("outcome batch: OHLC unavailable for {} (counterfactual {}): {}",
                        symbol, logIdRef, e.getMessage());
                return;
            }
            if (fetched.sourceEmpty()) {
                outcome = HypotheticalOutcome.skipped(
                        "no OHLC bars available for symbol over the whole lookback");
                complete = false;   // a data blackout is not a verdict on the signal
                noDataSymbols++;
                log.warn("outcome batch: no OHLC bars at all for {} (counterfactual {}) — writing "
                        + "an explicitly skipped counterfactual, not a stopped-out=false verdict",
                        symbol, logIdRef);
            } else {
                List<OhlcBar> bars = fetched.after();
                // SP8: the counterfactual enters at the OPEN OF THE FIRST BAR AFTER THE ANCHOR --
                // the first price actually reachable after the emission. reference_price is the
                // LIVE PRINT at emission (possibly mid-bar) and stays exactly that: it is what
                // CHASED_AWAY/BELOW_ANCHOR and the LLM signal context compare against,
                // and entering at it would credit the counterfactual with a pre-emission move no
                // executor could ever have captured. Passing the chosen price as walk()'s
                // assumedEntry keeps the stop (entry - 2.5 x atr) and rPerShare derived from the
                // same price. When no bar exists yet -- a signal too fresh, NOT a data blackout --
                // the walk still runs with reference_price, and the next night's batch re-walks
                // the same row under next_bar_open once a bar has appeared.
                if (bars.isEmpty()) {
                    entryPrice = referencePrice;
                    entrySource = "reference_price";
                } else {
                    BigDecimal open = bars.getFirst().open();
                    if (open != null && open.signum() > 0) {
                        entryPrice = open;
                        entrySource = "next_bar_open";
                    } else {
                        entryPrice = referencePrice;
                        entrySource = "reference_price";
                        log.warn("outcome batch: first bar after {} for {} has no usable open ({}) "
                                + "— walking from reference_price instead", anchor, symbol, open);
                    }
                }
                int horizon = resolveHorizon(signal);
                outcome = engine.walk(side, entryPrice, referenceAtr, null, bars, horizon);
                // A row is final only once BOTH the 60-bar R figures and the label horizon are filled:
                // tripleBarrierLabel answers "neither barrier hit" (false) only at bars >= horizon, and a row
                // completed earlier would freeze a null label forever (isComplete early return).
                complete = outcome.skippedReason() != null || bars.size() >= Math.max(60, horizon);
            }
        }

        ObjectNode hypo = mapper.createObjectNode();
        putOrNull(hypo, "r_after_20d", outcome.rAfter20d());
        putOrNull(hypo, "r_after_60d", outcome.rAfter60d());
        // Same honesty rule as processReject: a skipped walk evaluated nothing, so persisting its
        // placeholder wouldHaveStoppedOut=false would let "we could not look" read as "the stop was
        // never hit" -- and findVetoRows reads this column with no complete-filter.
        if (outcome.skippedReason() != null) hypo.putNull("would_have_stopped_out");
        else hypo.put("would_have_stopped_out", outcome.wouldHaveStoppedOut());
        if (outcome.skippedReason() != null) hypo.put("skipped_reason", outcome.skippedReason());
        else hypo.putNull("skipped_reason");
        // Absent, not null, on the three branches that never reached the walk: an absent key says
        // "no entry was ever chosen", while a null one would read as "chosen and unknown".
        if (entrySource != null) {
            hypo.put("entry_source", entrySource);
            hypo.put("entry_price", entryPrice);
        }

        outcomeLog.upsert(new OutcomeLogRow(
                "COUNTERFACTUAL", logIdRef, null, symbol, reasonCode,
                null, null, null, null,
                null, null, null,
                null, null, null,
                null, null,
                hypo, outcome.tripleBarrierLabel(),
                signal != null ? signal.source() : null,
                signal != null ? signal.agentVersion() : null,
                ruleVersions.active(),
                complete));
    }

    /** Bars after the signal date, plus whether the SOURCE served nothing at all.
     *  The two must not be conflated: an empty {@code after} with a non-empty history is the
     *  normal "signal is too fresh, no bars yet" case and stays retryable-but-evaluable, while
     *  {@code sourceEmpty} means the symbol has no price data to evaluate against at all. */
    private record BarsAfter(List<OhlcBar> after, boolean sourceEmpty) {}

    private BarsAfter fetchBarsAfter(String symbol, LocalDate signalDate) {
        long daysSince = ChronoUnit.DAYS.between(signalDate, LocalDate.now(ZoneOffset.UTC));
        int lookback = (int) Math.min(MAX_OHLC_LOOKBACK_DAYS, Math.max(90, daysSince + 90));
        List<OhlcBar> all = marketData.dailyOhlcHistory(symbol, lookback);
        return new BarsAfter(all.stream().filter(b -> b.date().isAfter(signalDate)).toList(),
                all.isEmpty());
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
