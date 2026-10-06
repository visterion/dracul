package de.visterion.dracul.executor;

import de.visterion.dracul.executor.SavingsPlanAudit.RowRef;
import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.executor.broker.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static de.visterion.dracul.executor.SavingsBuy.CLOSED_WITH_POSITION;
import static de.visterion.dracul.executor.SavingsBuy.CONSOLIDATED;
import static de.visterion.dracul.executor.SavingsBuy.CONSOLIDATING;
import static de.visterion.dracul.executor.SavingsBuy.EMERGENCY_EXIT;
import static de.visterion.dracul.executor.SavingsBuy.EXPIRED;
import static de.visterion.dracul.executor.SavingsBuy.PLACED;
import static de.visterion.dracul.executor.SavingsBuy.PLACING;
import static de.visterion.dracul.executor.SavingsBuy.REJECTED;
import static de.visterion.dracul.executor.SavingsBuy.UNPROTECTED;
import static de.visterion.dracul.executor.SavingsBuy.WINDOW_STOPPED;
import static de.visterion.dracul.executor.SavingsPlanAudit.fields;
import static de.visterion.dracul.executor.SavingsPlanAudit.plain;

/**
 * Consolidation of Tech-Sparplan adds (spec 2026-10-06 §5): turns whatever one US session did to an
 * add into the steady state — one position, one OPEN leg, ONE stop at the new average × 0.65.
 *
 * <p>Runs only inside the closed-market window and under the savings lease (the pipeline gates both),
 * on rows whose NY trade date is strictly before today's (session gate). Broker calls are never inside
 * a transaction; every status change is a CAS; the step-7 booking is one transaction. Every broker
 * call, transition and stage outcome is traced through {@link SavingsPlanAudit} (spec §8a).
 */
@Component
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class SavingsConsolidator {

    static final String STAGE = "consolidate";
    private static final Logger log = LoggerFactory.getLogger(SavingsConsolidator.class);
    private static final Set<String> IDLE =
            Set.of("waiting-session-gate", "waiting-adoption", "waiting-reconcile", "unchanged",
                    "other-connection", "position-missing");

    enum PlaceResult { PLACED, REJECTED, INDETERMINATE }

    record PlaceOutcome(PlaceResult result, String orderId, BigDecimal brokerStop, boolean narrow) {
        static PlaceOutcome placed(String id, BigDecimal brokerStop, boolean narrow) {
            return new PlaceOutcome(PlaceResult.PLACED, id, brokerStop, narrow);
        }
        static PlaceOutcome rejected() { return new PlaceOutcome(PlaceResult.REJECTED, null, null, false); }
        static PlaceOutcome indeterminate() { return new PlaceOutcome(PlaceResult.INDETERMINATE, null, null, false); }
    }

    /** One pass: connection, run id (may be null), lease pass UUID, the pass's clock reading. */
    record Ctx(String connection, String runId, String pass, Instant now) {
    }

    /** One broker read for one row: the position on the symbol, the open orders, the ref's orders. */
    record Broker(BrokerPosition bp, List<BrokerOrder> open, List<BrokerOrder> byRef) {
        BigDecimal qty() {
            return bp == null || bp.qty() == null ? BigDecimal.ZERO : bp.qty().abs();
        }
        BigDecimal avg() {
            return bp == null || bp.avgEntryPrice() == null ? null
                    : bp.avgEntryPrice().setScale(6, RoundingMode.HALF_UP);
        }
    }

    private final ExecutionGateway gateway;
    private final ExecutorPositionRepository positionRepo;
    private final ExecutorPositionLegRepository legRepo;
    private final SavingsPlanRepository savingsRepo;
    private final ExecutorIndicators indicators;
    private final ConvictionProfile convictionProfile;
    private final SavingsPlanSettings settings;
    private final SavingsPlanAudit audit;
    private final TransactionOperations tx;
    private final HardTriggerService hardTrigger;
    private final int atrPeriod;
    private final int swingPeriod;

    public SavingsConsolidator(ExecutionGateway gateway, ExecutorPositionRepository positionRepo,
            ExecutorPositionLegRepository legRepo, SavingsPlanRepository savingsRepo,
            ExecutorIndicators indicators, ConvictionProfile convictionProfile,
            SavingsPlanSettings settings, SavingsPlanAudit audit,
            @Qualifier("savingsPlanTransactions") TransactionOperations tx,
            HardTriggerService hardTrigger,
            @Value("${dracul.executor.atr-period:22}") int atrPeriod,
            @Value("${dracul.executor.swing-period:20}") int swingPeriod) {
        this.gateway = gateway;
        this.positionRepo = positionRepo;
        this.legRepo = legRepo;
        this.savingsRepo = savingsRepo;
        this.indicators = indicators;
        this.convictionProfile = convictionProfile;
        this.settings = settings;
        this.audit = audit;
        this.tx = tx;
        this.hardTrigger = hardTrigger;
        this.atrPeriod = atrPeriod;
        this.swingPeriod = swingPeriod;
    }

    /** Every in-flight row of {@code connection}, oldest first; renews the lease per row. */
    public Map<String, Integer> consolidateStage(String connection, String runId, String pass, Instant now) {
        Ctx c = new Ctx(connection, runId, pass, now);
        Map<String, Integer> tally = new TreeMap<>();
        List<SavingsBuy> rows = savingsRepo.findInFlight();
        for (SavingsBuy row : rows) {
            ExecutorPosition p = positionRepo.findById(row.positionId());
            if (p == null) {
                // V54's FK cascades a deleted position, so only a race lands here — never silent
                log.warn("savings-plan row failed row={} position={} symbol={} status={} run={} pass={} error=position-missing",
                        row.id(), row.positionId(), row.symbol(), row.status(), runId, pass);
                tally.merge("position-missing", 1, Integer::sum);
                continue;
            }
            if (!connection.equals(p.connection())) {
                tally.merge("other-connection", 1, Integer::sum);
                continue;
            }
            savingsRepo.renewLease(pass);
            String outcome;
            try {
                outcome = handleRow(row, p, c);
            } catch (RuntimeException e) {
                log.warn("savings-plan row failed row={} position={} symbol={} run={} pass={} error={}",
                        row.id(), row.positionId(), row.symbol(), runId, pass, e.toString(), e);
                outcome = "row-failed";
            }
            tally.merge(outcome, 1, Integer::sum);
        }
        boolean acted = tally.keySet().stream().anyMatch(k -> !IDLE.contains(k));
        String why = rows.isEmpty() ? "no-rows"
                : acted ? "-"
                : tally.containsKey("waiting-session-gate") ? "session-gate-not-passed"
                : tally.containsKey("waiting-adoption") ? "waiting-adoption"
                : tally.containsKey("position-missing") ? "position-missing"
                : tally.containsKey("other-connection") && tally.size() == 1 ? "other-connection"
                : "nothing-to-do";
        audit.stage(STAGE, runId, pass, acted ? "acted" : "did-nothing", why,
                fields("rows", rows.size(), "outcomes", SavingsPlanAudit.renderCounts(tally), "consolidated", tally.getOrDefault("consolidated", 0)));
        return tally;
    }

    String handleRow(SavingsBuy row, ExecutorPosition p, Ctx c) {
        // §5.1 global rule, FIRST (R3 M5), and the §6.2 catastrophe ordering — it only ends things,
        // so it ignores the session gate.
        if (!"OPEN".equals(p.status()) || p.pendingExitReason() != null || p.catastropheReason() != null) {
            return globalRule(row, p, c);
        }
        if (EMERGENCY_EXIT.equals(row.status())) return emergencyExit(row, p, c);
        if (PLACING.equals(row.status())) return adopt(row, p, c);
        if (!SavingsCalendar.sessionPassed(row.createdAt(), c.now())) return "waiting-session-gate";
        return consolidate(row, p, c);
    }

    /** §4.4 step 3: an indeterminate place. PLACED on any broker trace or a grown position;
     *  REJECTED only when both views are empty, the qty is unchanged AND a session has passed. */
    String adopt(SavingsBuy row, ExecutorPosition p, Ctx c) {
        Broker b = read(row, p, c);
        RowRef ref = RowRef.of(row);
        if (!b.byRef().isEmpty() || b.qty().compareTo(row.qtyBefore()) > 0) {
            String parentId = b.byRef().stream()
                    .filter(o -> !AdoptionCandidates.isStop(o) && !AdoptionCandidates.isTakeProfit(o))
                    .map(BrokerOrder::orderId).filter(Objects::nonNull).findFirst().orElse(null);
            if (!savingsRepo.markPlaced(row.id(), parentId, null)) return "unchanged";
            audit.transition(c.runId(), c.pass(), ref, PLACING, PLACED, "SAVINGS_CONSOLIDATE", "ADOPTED",
                    fields("by_ref_orders", b.byRef().size(), "bp_qty", b.qty(), "qty_before", row.qtyBefore(),
                            "entry_order_id", parentId),
                    "indeterminate place adopted: the broker shows the add");
            SavingsBuy adopted = savingsRepo.findById(row.id());
            if (adopted != null && SavingsCalendar.sessionPassed(adopted.createdAt(), c.now())) {
                return consolidate(adopted, p, c);
            }
            return "adopted";
        }
        if (SavingsCalendar.sessionPassed(row.createdAt(), c.now()) && b.qty().compareTo(row.qtyBefore()) == 0) {
            BigDecimal refund = refund(row, row.qty());
            Boolean done = tx.execute(status -> {
                if (!savingsRepo.transition(row.id(), PLACING, REJECTED)) {
                    status.setRollbackOnly();
                    return false;
                }
                if (refund.signum() > 0) savingsRepo.addCarry(row.positionId(), refund);
                audit.transition(c.runId(), c.pass(), ref, PLACING, REJECTED, "SAVINGS_CONSOLIDATE", "REJECTED",
                        fields("by_ref_orders", 0, "bp_qty", b.qty(), "qty_before", row.qtyBefore(),
                                "carry_refund_eur", refund),
                        "the broker never saw the add and a session has passed");
                return true;
            });
            return Boolean.TRUE.equals(done) ? "rejected" : "unchanged";
        }
        return "waiting-adoption";
    }

    /** §5.2 steps 1–7 for a row past the session gate. */
    String consolidate(SavingsBuy row, ExecutorPosition p, Ctx c) {
        // §5.3: an interrupted consolidation (crash between 6 and 7, indeterminate place, failed
        // restore) — keep exactly ONE stop, never place next to an unknown one.
        if (CONSOLIDATING.equals(row.status()) || UNPROTECTED.equals(row.status())) {
            return resume(row, p, c);
        }
        Broker b = read(row, p, c);
        BrokerOrder parent = liveParent(b, row);
        if (parent != null) {
            if (!cancel(row, p, c, parent.orderId(), "parent")) {
                cancelFailed(row, p, c, "cancel of parent " + parent.orderId() + " failed");
                return "escalated";
            }
            b = read(row, p, c);
            if (liveParent(b, row) != null) {
                cancelFailed(row, p, c, "parent " + parent.orderId() + " still working after its cancel");
                return "escalated";
            }
        }
        if (b.bp() == null || b.qty().signum() == 0 || b.avg() == null) {
            // reconcile owns a vanished position (matrix row 1); nothing to consolidate onto
            return "broker-position-missing";
        }
        BigDecimal bpQty = b.qty();
        // §5.2 step 3a (moved here from reconcile, R4 M3): a shortfall against leg 1 + the filled add.
        BigDecimal parentFilled = parentRows(b, row).map(BrokerOrder::filledQty).filter(Objects::nonNull)
                .max(java.util.Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        // without a visible parent, the gross fill: a marker from an earlier pass (window_stop_qty) is
        // added back, so the window stop is recognised again instead of booking the net fill
        BigDecimal addFilled = parentFilled.signum() > 0 ? parentFilled : row.grossFillQty(bpQty);
        BigDecimal missing = row.qtyBefore().add(addFilled).subtract(bpQty);
        if (missing.signum() > 0) return windowStop(row, p, b, c, bpQty, addFilled, missing);
        BigDecimal fillQty = bpQty.subtract(row.qtyBefore());
        if (fillQty.signum() <= 0) return expire(row, p, b, c);
        return consolidateFilled(row, p, b, c, bpQty, fillQty, null);
    }

    String consolidateFilled(SavingsBuy row, ExecutorPosition p, Broker b, Ctx c, BigDecimal bpQty,
            BigDecimal fillQty, BigDecimal fillPriceOverride) {
        BigDecimal avg = b.avg();
        BigDecimal fillPrice = fillPriceOverride != null ? fillPriceOverride : derivedFillPrice(avg, fillQty, row);
        BigDecimal target = target(avg, p);
        if (!savingsRepo.markConsolidating(row.id(), row.status(), bpQty, target, avg)) return "unchanged";
        String expected = UNPROTECTED.equals(row.status()) ? UNPROTECTED : CONSOLIDATING;
        audit.transition(c.runId(), c.pass(), RowRef.of(row), row.status(), expected, "SAVINGS_CONSOLIDATE",
                "CONSOLIDATING", fields("bp_qty", bpQty, "bp_avg", avg, "qty_before", row.qtyBefore(),
                        "avg_before", row.avgBefore(), "stop_before", row.stopBefore(), "fill_qty", fillQty,
                        "fill_price", fillPrice, "target_qty", bpQty, "target_stop", target,
                        "trim_count", p.trimCount(), "place_first", settings.placeFirst()),
                "step 4: consolidation target");
        List<BrokerOrder> oldStops = liveSellStops(b, p.symbol());
        PlaceOutcome placed = settings.placeFirst()
                ? placeThenCancel(row, expected, p, c, bpQty, target, oldStops)
                : cancelThenPlace(row, expected, p, c, bpQty, target, oldStops);
        if (placed == null) return "escalated";
        return book(row, expected, p, c, bpQty, avg, fillQty, fillPrice, target, placed);
    }

    /** §5.3 resume: recompute the target from the CURRENT broker state; keep new_stop_order_id if it
     *  is live and sized to the broker qty, otherwise one live stop with qty == bp.qty and price ==
     *  target; cancel every other live SELL stop and book step 7. If none qualifies, re-place the
     *  target (UNPROTECTED + CRITICAL while the live stops cover less than bp.qty). A failed cancel/place goes
     *  through the same coverage recovery as a first consolidation ({@link #recover}). */
    String resume(SavingsBuy row, ExecutorPosition p, Ctx c) {
        Broker b = read(row, p, c);
        if (b.bp() == null || b.qty().signum() == 0 || b.avg() == null) return "broker-position-missing";
        BigDecimal bpQty = b.qty();
        BigDecimal avg = b.avg();
        BigDecimal target = target(avg, p);
        // Final review I2: the GROSS add fill. The parent's own filled qty when the broker still shows
        // it (also right when the position shrank below qty_before since — never a clamped 0 that
        // refunds the whole carry); else bp.qty − qty_before plus what a window stop already sold
        // (window_stop_qty, TRIMmed in step 3a) — never the net fill, which over-refunds carry.
        BrokerOrder parentFill = parentRows(b, row)
                .filter(o -> o.filledQty() != null && o.filledQty().signum() > 0)
                .max(java.util.Comparator.comparing(BrokerOrder::filledQty)).orElse(null);
        BigDecimal fillQty = parentFill != null ? parentFill.filledQty() : row.grossFillQty(bpQty);
        BigDecimal fillPrice = parentFill != null && parentFill.avgFillPrice() != null ? parentFill.avgFillPrice()
                : derivedFillPrice(avg, fillQty, row);
        String status = row.status();
        if (!savingsRepo.markConsolidating(row.id(), status, bpQty, target, avg)) return "unchanged";
        List<BrokerOrder> live = liveSellStops(b, p.symbol());
        BrokerOrder keep = live.stream()
                .filter(o -> o.orderId().equals(row.newStopOrderId())
                        && o.qty() != null && o.qty().abs().compareTo(bpQty) == 0)
                .findFirst()
                .orElseGet(() -> live.stream()
                        .filter(o -> o.qty() != null && o.qty().abs().compareTo(bpQty) == 0
                                && o.stopPrice() != null && o.stopPrice().compareTo(target) == 0)
                        .findFirst().orElse(null));
        audit.transition(c.runId(), c.pass(), RowRef.of(row), status, status, "SAVINGS_CONSOLIDATE", "RESUME",
                fields("bp_qty", bpQty, "bp_avg", avg, "qty_before", row.qtyBefore(), "fill_qty", fillQty,
                        "fill_source", parentFill != null ? "parent" : "bp_qty",
                        "window_stop_qty", row.windowStopQty(),
                        "fill_price", fillPrice, "target_qty", bpQty, "target_stop", target,
                        "new_stop_order_id", row.newStopOrderId(), "live_stops", ids(live),
                        "keep", keep == null ? null : keep.orderId()),
                "resume of an interrupted consolidation: keep exactly one stop");
        if (keep != null) {
            for (BrokerOrder o : live) {
                if (o.orderId().equals(keep.orderId())) continue;
                if (!cancel(row, p, c, o.orderId(), "surplus-stop")) {
                    // the kept stop is live and sized to bp.qty, so the shares stay covered
                    audit.escalate(c.runId(), c.pass(), RowRef.of(row), p.symbol(), "CONSOLIDATE_CANCEL_FAILED",
                            "WARN", fields("order", o.orderId(), "keep", keep.orderId(), "status", status),
                            "a surplus stop could not be cancelled on resume; the kept stop " + keep.orderId()
                                    + " covers bp_qty, retried next closed pass");
                    return "escalated";
                }
            }
            BigDecimal rests = keep.stopPrice() != null ? keep.stopPrice() : target;
            return book(row, status, p, c, bpQty, avg, fillQty, fillPrice, target,
                    PlaceOutcome.placed(keep.orderId(), rests, rests.compareTo(target) > 0));
        }
        String expected = status;
        BigDecimal coverage = qtySum(live);
        if (coverage.compareTo(bpQty) < 0) {
            // same coverage rule as recover(): live stops below bp.qty leave shares without a stop
            unprotected(row, status, p, c, (live.isEmpty() ? "no live stop on " + p.symbol()
                    : "the live stops on " + p.symbol() + " cover only " + plain(coverage) + " of bp_qty "
                            + plain(bpQty)) + " while add " + row.clientRef() + " is " + status);
            expected = UNPROTECTED;
        }
        PlaceOutcome placed = settings.placeFirst()
                ? placeThenCancel(row, expected, p, c, bpQty, target, live)
                : cancelThenPlace(row, expected, p, c, bpQty, target, live);
        if (placed == null) return "escalated";
        return book(row, expected, p, c, bpQty, avg, fillQty, fillPrice, target, placed);
    }

    /** Step 3 with fill_qty = 0: EXPIRED, carry refunded, any live child stop cancelled, leg 1 untouched. */
    String expire(SavingsBuy row, ExecutorPosition p, Broker b, Ctx c) {
        List<String> cancelled = new ArrayList<>();
        for (BrokerOrder o : liveSellStops(b, p.symbol())) {
            if (o.orderId().equals(p.stopOrderId())) continue;
            if (cancel(row, p, c, o.orderId(), "child")) cancelled.add(o.orderId());
        }
        BigDecimal refund = refund(row, row.qty());
        Boolean done = tx.execute(status -> {
            if (!savingsRepo.finish(row.id(), row.status(), EXPIRED, BigDecimal.ZERO, null, null, null)) {
                status.setRollbackOnly();
                return false;
            }
            if (refund.signum() > 0) savingsRepo.addCarry(row.positionId(), refund);
            audit.transition(c.runId(), c.pass(), RowRef.of(row), row.status(), EXPIRED, "SAVINGS_CONSOLIDATE",
                    "EXPIRED", fields("bp_qty", b.qty(), "qty_before", row.qtyBefore(), "fill_qty", BigDecimal.ZERO,
                            "carry_refund_eur", refund, "cancelled_child_stops", String.join(",", cancelled)),
                    "the add did not fill; leg 1 unchanged, its money back to carry");
            return true;
        });
        return Boolean.TRUE.equals(done) ? "expired" : "unchanged";
    }

    /** Step 7: position, leg 1, row and decision log in ONE transaction. */
    String book(SavingsBuy row, String expected, ExecutorPosition p, Ctx c, BigDecimal bpQty, BigDecimal avg,
            BigDecimal fillQty, BigDecimal fillPrice, BigDecimal target, PlaceOutcome placed) {
        BigDecimal refund = refund(row, row.qty().subtract(fillQty == null ? BigDecimal.ZERO : fillQty));
        boolean movedDown = row.stopBefore() != null && target.compareTo(row.stopBefore()) < 0;
        List<ExecutorPositionLeg> legs = legRepo.findOpenByPosition(p.id());
        ExecutorPositionLeg leg = legs.size() == 1 ? legs.getFirst() : null;
        Boolean done = tx.execute(status -> {
            if (!savingsRepo.finish(row.id(), expected, CONSOLIDATED, fillQty, fillPrice, avg, target)) {
                status.setRollbackOnly();
                return false;
            }
            positionRepo.bookSavingsQtyAndAvg(p.id(), bpQty, avg);
            positionRepo.bookSavingsStop(p.id(), placed.orderId(), target, placed.brokerStop(), placed.narrow());
            if (leg != null) legRepo.setStopAndQty(leg.id(), placed.orderId(), bpQty);
            if (refund.signum() > 0) savingsRepo.addCarry(row.positionId(), refund);
            audit.transition(c.runId(), c.pass(), RowRef.of(row), expected, CONSOLIDATED, "SAVINGS_CONSOLIDATE",
                    "CONSOLIDATED", fields("bp_qty", bpQty, "bp_avg", avg, "fill_qty", fillQty,
                            "fill_price", fillPrice, "target_qty", bpQty, "target_stop", target,
                            "new_stop_order_id", placed.orderId(), "broker_stop", placed.brokerStop(),
                            "broker_stop_narrow", placed.narrow(), "stop_before", row.stopBefore(),
                            "stop_moved_down", movedDown, "carry_refund_eur", refund,
                            "open_legs", legs.size(), "leg_id", leg == null ? null : leg.id()),
                    movedDown
                            ? "consolidated; the add was below the old average, so the stop moves DOWN — audited bypass of StopRatchetGuard, initial_stop unchanged"
                            : "consolidated into one position, one leg, one stop");
            return true;
        });
        if (Boolean.TRUE.equals(done) && leg == null) {
            legsNotSingle(row, p, c, legs.size(), "step 7 booked the position at qty " + plain(bpQty)
                    + " and stop " + placed.orderId() + " but no leg was updated");
        }
        return Boolean.TRUE.equals(done) ? "consolidated" : "unchanged";
    }

    // ---- §5.2 step 3a, §5.1 global rule, §6.1 row 2 (D8) ------------------------------------------

    /** §5.2 step 3a: write the window-stop TRIM exactly once (CAS on window_stop_qty, same transaction),
     *  then WINDOW_STOPPED if no add share remains, else consolidate on the broker qty. */
    String windowStop(SavingsBuy row, ExecutorPosition p, Broker b, Ctx c, BigDecimal bpQty,
            BigDecimal addFilled, BigDecimal missing) {
        RowRef ref = RowRef.of(row);
        BigDecimal addPrice = parentRows(b, row).map(BrokerOrder::avgFillPrice).filter(Objects::nonNull)
                .findFirst().orElse(null);
        if (row.windowStopQty() == null) {
            BrokerOrder stopFill = windowStopFill(row, p, c);
            if (stopFill != null) {
                boolean shrinkLeg1 = bpQty.compareTo(row.qtyBefore()) < 0;
                List<ExecutorPositionLeg> legs = shrinkLeg1 ? legRepo.findOpenByPosition(p.id()) : List.of();
                ExecutorPositionLeg leg = legs.size() == 1 ? legs.getFirst() : null;
                Boolean marked = tx.execute(status -> {
                    if (!savingsRepo.markWindowStop(row.id(), missing)) {
                        status.setRollbackOnly();
                        return false;
                    }
                    audit.record(c.runId(), p.symbol(), "TRIM", "SAVINGS_WINDOW_STOP",
                            fields("qty_closed", missing, "qty_remaining", bpQty, "price", stopFill.avgFillPrice(),
                                    "position_id", p.id(), "entry_price", addPrice, "savings_buy_id", row.id(),
                                    "order_id_filled", stopFill.orderId()),
                            "a stop filled inside the add's session: " + plain(missing) + " shares at "
                                    + plain(stopFill.avgFillPrice()));
                    if (shrinkLeg1) {
                        positionRepo.syncQty(p.id(), bpQty);
                        if (leg != null) legRepo.syncLegQty(leg.id(), bpQty);
                    }
                    audit.transition(c.runId(), c.pass(), ref, row.status(), row.status(), "SAVINGS_CONSOLIDATE",
                            "WINDOW_STOP_MARKED", fields("window_stop_qty", missing, "bp_qty", bpQty,
                                    "qty_before", row.qtyBefore(), "add_filled", addFilled,
                                    "stop_fill_price", stopFill.avgFillPrice(), "stop_order_id", stopFill.orderId(),
                                    "leg1_shrunk", shrinkLeg1),
                            "window-stop TRIM written once; trim_count unchanged");
                    return true;
                });
                if (!Boolean.TRUE.equals(marked)) return "unchanged";
                if (shrinkLeg1 && leg == null) {
                    legsNotSingle(row, p, c, legs.size(), "the window stop shrank the position to "
                            + plain(bpQty) + " but no leg was");
                }
            } else {
                if (!audit.escalatedFor("SAVINGS_QTY_UNEXPLAINED", row.id())) {
                    audit.escalate(c.runId(), c.pass(), ref, p.symbol(), "SAVINGS_QTY_UNEXPLAINED", "WARN",
                            fields("detail", "bp_qty:" + plain(bpQty) + ",qty_before:" + plain(row.qtyBefore()),
                                    "bp_qty", bpQty, "qty_before", row.qtyBefore(), "add_filled", addFilled,
                                    "missing", missing),
                            "the broker holds fewer shares than leg 1 plus the filled add and no stop fill explains it"
                                    + " — the book follows the broker quantity");
                }
                // never leave the book at qty_before while the broker holds fewer (review r1 Important 1)
                if (bpQty.compareTo(row.qtyBefore()) < 0) syncBookToBroker(row, p, c, bpQty);
            }
        }
        // no add share ever filled: the add expired — carry refunded, live child stop cancelled
        if (addFilled.signum() == 0) return expire(row, p, b, c);
        BigDecimal remainingAdd = bpQty.subtract(row.qtyBefore().min(bpQty));
        if (remainingAdd.signum() <= 0) {
            Boolean done = tx.execute(status -> {
                if (!savingsRepo.finish(row.id(), row.status(), WINDOW_STOPPED, addFilled, addPrice, null, null)) {
                    status.setRollbackOnly();
                    return false;
                }
                audit.transition(c.runId(), c.pass(), ref, row.status(), WINDOW_STOPPED, "SAVINGS_CONSOLIDATE",
                        "WINDOW_STOPPED", fields("bp_qty", bpQty, "qty_before", row.qtyBefore(),
                                "fill_qty", addFilled, "fill_price", addPrice, "window_stop_qty", missing),
                        "every add share left through a stop inside the session; no carry refund");
                return true;
            });
            return Boolean.TRUE.equals(done) ? "window-stopped" : "unchanged";
        }
        return consolidateFilled(row, p, b, c, bpQty, addFilled, addPrice);
    }

    /** An unexplained shortfall below qty_before: position and leg-1 qty := bp.qty (idempotent, so a
     *  repeat pass before the row is terminal is harmless); a non-single leg set is escalated. */
    void syncBookToBroker(SavingsBuy row, ExecutorPosition p, Ctx c, BigDecimal bpQty) {
        List<ExecutorPositionLeg> legs = legRepo.findOpenByPosition(p.id());
        ExecutorPositionLeg leg = legs.size() == 1 ? legs.getFirst() : null;
        tx.execute(status -> {
            positionRepo.syncQty(p.id(), bpQty);
            if (leg != null) legRepo.syncLegQty(leg.id(), bpQty);
            audit.transition(c.runId(), c.pass(), RowRef.of(row), row.status(), row.status(), "SAVINGS_CONSOLIDATE",
                    "UNEXPLAINED_QTY_SYNC", fields("book_qty", p.qty(), "bp_qty", bpQty, "qty_before", row.qtyBefore(),
                            "open_legs", legs.size(), "leg_id", leg == null ? null : leg.id()),
                    "unexplained shortfall: position and leg 1 synced to the broker qty, never left at qty_before");
            return true;
        });
        if (leg == null) {
            legsNotSingle(row, p, c, legs.size(), "an unexplained shortfall synced the position to "
                    + plain(bpQty) + " but no leg was");
        }
    }

    /** The child's fill by id, else a SELL-stop fill on the symbol since the add (not leg 1's stop). */
    BrokerOrder windowStopFill(SavingsBuy row, ExecutorPosition p, Ctx c) {
        List<BrokerOrder> fills = audit.broker(STAGE, c.runId(), c.pass(), "filledOrdersSince",
                "row=" + row.id() + " since=" + row.createdAt(),
                () -> gateway.filledOrdersSince(c.connection(), row.createdAt()), l -> "count=" + l.size());
        if (row.childStopOrderId() != null) {
            BrokerOrder child = fills.stream().filter(o -> row.childStopOrderId().equals(o.orderId()))
                    .findFirst().orElse(null);
            if (child != null) return child;
        }
        return fills.stream()
                .filter(o -> o.status() == OrderStatus.FILLED && p.symbol().equalsIgnoreCase(o.symbol()))
                .filter(o -> AdoptionCandidates.isStop(o) && AdoptionCandidates.looseSide(o, "sell"))
                .filter(o -> !Objects.equals(o.orderId(), p.stopOrderId()))
                .findFirst().orElse(null);
    }

    /** §5.1 global rule: the position is CLOSED, has a pending exit, or is catastrophe-flagged. */
    String globalRule(SavingsBuy row, ExecutorPosition p, Ctx c) {
        Broker b = read(row, p, c);
        BrokerOrder parent = liveParent(b, row);
        if (parent != null) {
            if (!cancel(row, p, c, parent.orderId(), "parent")) {
                cancelFailed(row, p, c, "global rule: cancel of parent " + parent.orderId() + " failed");
                return "escalated";
            }
            b = read(row, p, c);
            if (liveParent(b, row) != null) {
                cancelFailed(row, p, c, "global rule: parent " + parent.orderId() + " still working after its cancel");
                return "escalated";
            }
        }
        BigDecimal bpQty = b.qty();
        boolean bookShares = !EMERGENCY_EXIT.equals(row.status()) && "OPEN".equals(p.status())
                && b.avg() != null && bpQty.compareTo(row.qtyBefore()) > 0;
        BigDecimal avg = bookShares ? b.avg() : null;
        BigDecimal fillQty = bookShares ? row.grossFillQty(bpQty) : row.fillQty();
        BigDecimal fillPrice = bookShares ? derivedFillPrice(avg, fillQty, row) : row.fillPrice();
        String why = !"OPEN".equals(p.status()) ? "position_status=" + p.status()
                : p.pendingExitReason() != null ? "pending_exit_reason=" + p.pendingExitReason()
                : "catastrophe_reason=" + p.catastropheReason();
        boolean parentCancelled = parent != null;
        List<ExecutorPositionLeg> legs = bookShares ? legRepo.findOpenByPosition(p.id()) : List.of();
        ExecutorPositionLeg leg = legs.size() == 1 ? legs.getFirst() : null;
        Boolean done = tx.execute(status -> {
            if (!savingsRepo.finish(row.id(), row.status(), CLOSED_WITH_POSITION, fillQty, fillPrice, avg, null)) {
                status.setRollbackOnly();
                return false;
            }
            if (bookShares) {
                positionRepo.bookSavingsQtyAndAvg(p.id(), bpQty, avg);
                if (leg != null) legRepo.syncLegQty(leg.id(), bpQty);
            }
            audit.transition(c.runId(), c.pass(), RowRef.of(row), row.status(), CLOSED_WITH_POSITION,
                    "SAVINGS_CONSOLIDATE", "CLOSED_WITH_POSITION",
                    fields("why", why, "bp_qty", bpQty, "qty_before", row.qtyBefore(), "fill_qty", fillQty,
                            "fill_price", fillPrice, "booked_add_shares", bookShares,
                            "parent_cancelled", parentCancelled, "open_legs", bookShares ? legs.size() : null,
                            "leg_id", leg == null ? null : leg.id()),
                    "the position is leaving the book — add terminal, no carry refund"
                            + (bookShares ? "; the filled add shares were booked so the exit sells what the book holds" : ""));
            return true;
        });
        if (Boolean.TRUE.equals(done) && bookShares && leg == null) {
            legsNotSingle(row, p, c, legs.size(), "the global rule booked the position at qty " + plain(bpQty)
                    + " but no leg was updated");
        }
        return Boolean.TRUE.equals(done) ? "closed-with-position" : "unchanged";
    }

    /** §6.1 row 2, next closed pass: cancel ONLY the BUY parent (Agora's flatten handles the stops),
     *  re-sync qty if it changed, then the hard-exit path; terminal in the same step. */
    String emergencyExit(SavingsBuy row, ExecutorPosition p, Ctx c) {
        Broker b = read(row, p, c);
        if (b.bp() == null || b.qty().signum() == 0) return "waiting-reconcile";
        BrokerOrder parent = liveParent(b, row);
        if (parent != null) {
            if (!cancel(row, p, c, parent.orderId(), "parent")) {
                cancelFailed(row, p, c, "emergency step: cancel of parent " + parent.orderId() + " failed");
                return "escalated";
            }
            b = read(row, p, c);
            if (liveParent(b, row) != null) {
                // never flatten while a BUY rests: it could fill into the closed book (R1 M2)
                cancelFailed(row, p, c, "emergency step: parent " + parent.orderId() + " still working after its cancel");
                return "escalated";
            }
            if (b.bp() == null || b.qty().signum() == 0) return "waiting-reconcile";
        }
        BigDecimal bpQty = b.qty();
        ExecutorPosition current = p;
        if (p.qty() == null || p.qty().compareTo(bpQty) != 0) {
            List<ExecutorPositionLeg> legs = legRepo.findOpenByPosition(p.id());
            ExecutorPositionLeg leg = legs.size() == 1 ? legs.getFirst() : null;
            positionRepo.syncQty(p.id(), bpQty);
            if (leg != null) legRepo.syncLegQty(leg.id(), bpQty);
            ExecutorPosition fresh = positionRepo.findById(p.id());
            if (fresh != null) current = fresh;
            audit.transition(c.runId(), c.pass(), RowRef.of(row), EMERGENCY_EXIT, EMERGENCY_EXIT,
                    "SAVINGS_CONSOLIDATE", "EMERGENCY_QTY_RESYNC", fields("book_qty", p.qty(), "bp_qty", bpQty,
                            "open_legs", legs.size(), "leg_id", leg == null ? null : leg.id()),
                    "broker qty changed after the parent cancel; book re-synced before the flatten");
            if (leg == null) {
                legsNotSingle(row, p, c, legs.size(), "the emergency step re-synced the position to "
                        + plain(bpQty) + " but no leg was");
            }
        }
        ExecutorPosition flattenTarget = current;
        BigDecimal close = close(p.symbol());
        String measured = "SAVINGS_EMERGENCY: the pre-add emergency stop filled while add " + row.clientRef()
                + " was in flight — the add shares are sold too (D8)";
        HardTriggerService.HardExitOutcome out = audit.broker(STAGE, c.runId(), c.pass(), "flatten",
                "row=" + row.id() + " symbol=" + p.symbol() + " qty=" + plain(bpQty) + " reason=HARD_STOP",
                () -> hardTrigger.hardExit(flattenTarget, close, measured, c.runId()), o -> "outcome=" + o);
        if (out == HardTriggerService.HardExitOutcome.SUBMITTED) {
            if (savingsRepo.finish(row.id(), EMERGENCY_EXIT, CLOSED_WITH_POSITION, null, null, null, null)) {
                audit.transition(c.runId(), c.pass(), RowRef.of(row), EMERGENCY_EXIT, CLOSED_WITH_POSITION,
                        "SAVINGS_CONSOLIDATE", "CLOSED_WITH_POSITION",
                        fields("bp_qty", bpQty, "fill_qty", row.fillQty(), "fill_price", row.fillPrice(),
                                "exit_reason", "HARD_STOP"),
                        "D8: the add shares are flattened with the position");
            }
            return "emergency-flattened";
        }
        if (out == HardTriggerService.HardExitOutcome.POSITION_GONE) {
            return "waiting-reconcile";   // matrix row 1 in the next reconcile; never a retry here
        }
        return "escalated";               // HardTriggerService escalated; stops untouched, retried next pass
    }

    // ---- step 6: the two legal orders ---------------------------------------------------------

    PlaceOutcome cancelThenPlace(SavingsBuy row, String expected, ExecutorPosition p, Ctx c,
            BigDecimal qty, BigDecimal target, List<BrokerOrder> oldStops) {
        RowRef ref = RowRef.of(row);
        List<BrokerOrder> cancelled = new ArrayList<>();
        for (BrokerOrder old : oldStops) {
            if (!cancel(row, p, c, old.orderId(), "old-stop")) {
                Recovery r = recover(row, expected, p, c, qty, oldStops);
                audit.escalate(c.runId(), c.pass(), ref, p.symbol(), "CONSOLIDATE_CANCEL_FAILED", "WARN",
                        fields("order", old.orderId(), "cancelled_before", ids(cancelled),
                                "restored_old_stops", ids(r.restored()), "coverage", r.coverage(), "bp_qty", qty,
                                "covered", r.covered()),
                        r.covered()
                                ? "cancel of an old stop failed (cancel-first): every old stop no longer live was "
                                        + "re-placed at its old qty/price and live stops cover bp_qty; no new stop placed, row stays " + expected
                                : "cancel of an old stop failed (cancel-first) and the live stops do not cover bp_qty "
                                        + "after the restore — row is UNPROTECTED (see CONSOLIDATE_UNPROTECTED)");
                return null;
            }
            cancelled.add(old);
        }
        PlaceOutcome o = placeTarget(row, expected, p, c, qty, target);
        if (o.result() == PlaceResult.PLACED) return o;
        if (o.result() == PlaceResult.REJECTED) {
            Recovery r = recover(row, expected, p, c, qty, oldStops);
            audit.escalate(c.runId(), c.pass(), ref, p.symbol(), "CONSOLIDATE_PLACE_FAILED",
                    r.covered() ? "WARN" : "CRITICAL",
                    fields("target", target, "qty", qty, "restored_old_stops", ids(r.restored()),
                            "coverage", r.coverage(), "covered", r.covered()),
                    r.covered()
                            ? "target and band stop both rejected; the old stops were re-placed at their old qty/price and cover bp_qty"
                            : "target and band stop both rejected and the live stops do not cover bp_qty after the "
                                    + "restore — row is UNPROTECTED (see CONSOLIDATE_UNPROTECTED)");
            return null;
        }
        unprotected(row, expected, p, c, "the target stop's place got no verdict after the old stops were cancelled");
        return null;
    }

    PlaceOutcome placeThenCancel(SavingsBuy row, String expected, ExecutorPosition p, Ctx c,
            BigDecimal qty, BigDecimal target, List<BrokerOrder> oldStops) {
        RowRef ref = RowRef.of(row);
        PlaceOutcome o = placeTarget(row, expected, p, c, qty, target);
        if (o.result() != PlaceResult.PLACED) {
            BigDecimal coverage = qtySum(oldStops);
            boolean covered = coverage.compareTo(qty) >= 0;
            audit.escalate(c.runId(), c.pass(), ref, p.symbol(), "CONSOLIDATE_PLACE_FAILED", "WARN",
                    fields("target", target, "qty", qty, "result", o.result(), "coverage", coverage,
                            "covered", covered),
                    !covered
                            ? "no new stop (place-first) and the old stops do not cover bp_qty — row goes UNPROTECTED"
                            : o.result() == PlaceResult.REJECTED
                            ? "target and band stop both rejected (place-first): the old stops were never cancelled and stay live"
                            : "no verdict on the target stop (place-first): the old stops stay live; the next closed pass keeps exactly one");
            if (!covered) {
                unprotected(row, expected, p, c, "no new stop (place-first) and the live old stops cover only "
                        + plain(coverage) + " of bp_qty " + plain(qty));
            }
            return null;
        }
        for (BrokerOrder old : oldStops) {
            if (!cancel(row, p, c, old.orderId(), "old-stop")) {
                audit.escalate(c.runId(), c.pass(), ref, p.symbol(), "CONSOLIDATE_CANCEL_FAILED", "WARN",
                        fields("order", old.orderId(), "new_stop_order_id", o.orderId()),
                        "the new stop is live but an old stop could not be cancelled (place-first); the next closed pass keeps exactly one");
                return null;
            }
        }
        return o;
    }

    PlaceOutcome placeTarget(SavingsBuy row, String expected, ExecutorPosition p, Ctx c,
            BigDecimal qty, BigDecimal target) {
        String id;
        try {
            id = placeStop(row, p, c, qty, target, "target");
        } catch (BrokerRejectedException e) {
            return placeAtBand(row, expected, p, c, qty, target);
        } catch (BrokerUnavailableException e) {
            return PlaceOutcome.indeterminate();
        }
        persistNewStop(row, expected, c, id, target);
        return PlaceOutcome.placed(id, target, false);
    }

    /** §5.2 step 6 fallback: place at max(target, roundStop(close × 0.80)), then a named-leg modify
     *  to the target; a rejected modify leaves the tighter (protective) band stop — CONSOLIDATE_NARROW. */
    PlaceOutcome placeAtBand(SavingsBuy row, String expected, ExecutorPosition p, Ctx c,
            BigDecimal qty, BigDecimal target) {
        BigDecimal close = close(p.symbol());
        if (close == null) return PlaceOutcome.rejected();
        BigDecimal band = target.max(TickSize.roundStop("BUY",
                close.multiply(BigDecimal.ONE.subtract(convictionProfile.entryBrokerStopPct()))));
        if (band.compareTo(target) == 0) return PlaceOutcome.rejected();
        String id;
        try {
            id = placeStop(row, p, c, qty, band, "band");
        } catch (BrokerRejectedException e) {
            return PlaceOutcome.rejected();
        } catch (BrokerUnavailableException e) {
            return PlaceOutcome.indeterminate();
        }
        persistNewStop(row, expected, c, id, band);
        try {
            audit.broker(STAGE, c.runId(), c.pass(), "modifyBracket",
                    "row=" + row.id() + " symbol=" + p.symbol() + " order=" + id + " stop=" + plain(target),
                    () -> gateway.modifyBracket(c.connection(), id, p.symbol(), target, null, id, null),
                    r -> "accepted=" + r.accepted());
            return PlaceOutcome.placed(id, target, false);
        } catch (BrokerRejectedException e) {
            audit.escalate(c.runId(), c.pass(), RowRef.of(row), p.symbol(), "CONSOLIDATE_NARROW", "WARN",
                    fields("band", band, "target", target, "new_stop_order_id", id, "reject_code", e.rejectCode()),
                    "the new stop rests at the band " + plain(band) + " instead of the target " + plain(target)
                            + " — tighter, so protective; widenNarrowLeg does not retry, the next consolidation tries the target again");
            return PlaceOutcome.placed(id, band, true);
        } catch (BrokerUnavailableException e) {
            // no verdict on the modify: the stop rests at the band or the target — book the band
            // (the tighter, protective reading); the next consolidation tries the target again
            audit.escalate(c.runId(), c.pass(), RowRef.of(row), p.symbol(), "CONSOLIDATE_NARROW", "WARN",
                    fields("band", band, "target", target, "new_stop_order_id", id, "reject_code", "indeterminate"),
                    "no verdict on the modify to the target " + plain(target) + "; booked at the band "
                            + plain(band) + " (tighter, so protective) — the next consolidation tries the target again");
            return PlaceOutcome.placed(id, band, true);
        }
    }

    /** Result of {@link #recover}: what was re-placed, the live-stop qty afterwards, and whether it
     *  covers bp.qty (false ⇒ the row is UNPROTECTED and CRITICAL was raised). */
    record Recovery(List<BrokerOrder> restored, BigDecimal coverage, boolean covered) {
    }

    /** §5.3 cancel-first failure: a cancel that failed (indeterminate, or refused as ORDER_NOT_FOUND)
     *  may still have taken effect, so the orders are read again and EVERY old stop that is no
     *  longer live is re-placed at its old qty/price — not only the ones this pass saw cancel. If
     *  the live stops then do not cover {@code bpQty}, or the re-read itself fails, the row goes
     *  UNPROTECTED: shares must never sit without a stop while the row is trigger-excluded. */
    Recovery recover(SavingsBuy row, String expected, ExecutorPosition p, Ctx c, BigDecimal bpQty,
            List<BrokerOrder> oldStops) {
        List<BrokerOrder> live;
        try {
            List<BrokerOrder> open = audit.broker(STAGE, c.runId(), c.pass(), "orders",
                    "row=" + row.id() + " why=coverage-after-failure",
                    () -> gateway.orders(c.connection()), l -> "count=" + l.size());
            live = liveSellStops(new Broker(null, open, List.of()), p.symbol());
        } catch (BrokerUnavailableException e) {
            unprotected(row, expected, p, c, "the order re-read after a failed step 6 got no answer, "
                    + "so the stop coverage is unknown: " + e.getMessage());
            return new Recovery(List.of(), BigDecimal.ZERO, false);
        }
        Set<String> liveIds = live.stream().map(BrokerOrder::orderId).collect(Collectors.toSet());
        List<BrokerOrder> gone = oldStops.stream().filter(o -> !liveIds.contains(o.orderId())).toList();
        if (!restore(row, expected, p, c, gone)) return new Recovery(gone, qtySum(live), false);
        BigDecimal coverage = qtySum(live).add(qtySum(gone));
        if (coverage.compareTo(bpQty) < 0) {
            unprotected(row, expected, p, c, "after the restore the live stops cover only " + plain(coverage)
                    + " of bp_qty " + plain(bpQty));
            return new Recovery(gone, coverage, false);
        }
        return new Recovery(gone, coverage, true);
    }

    private static BigDecimal qtySum(List<BrokerOrder> orders) {
        return orders.stream().map(BrokerOrder::qty).filter(Objects::nonNull).map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Re-places every stop already cancelled at its OLD qty/price (levels Saxo already accepted). A
     *  re-placed leg-1 stop is repointed in the book. @return false when protection could not be
     *  restored — the row is then UNPROTECTED (CRITICAL). */
    boolean restore(SavingsBuy row, String expected, ExecutorPosition p, Ctx c, List<BrokerOrder> cancelled) {
        for (BrokerOrder old : cancelled) {
            if (old.qty() == null || old.stopPrice() == null) {
                unprotected(row, expected, p, c, "old stop " + old.orderId() + " carries no qty/price to re-place");
                return false;
            }
            String newId;
            try {
                newId = placeStop(row, p, c, old.qty(), old.stopPrice(), "restore-" + old.orderId());
            } catch (BrokerUnavailableException e) {   // incl. BrokerRejectedException (a subclass)
                unprotected(row, expected, p, c, "re-placing old stop " + old.orderId() + " failed: " + e.getMessage());
                return false;
            }
            if (old.orderId().equals(p.stopOrderId())) {
                positionRepo.setStopOrderId(p.id(), newId);
                List<ExecutorPositionLeg> legs = legRepo.findOpenByPosition(p.id());
                ExecutorPositionLeg leg = legs.size() == 1 ? legs.getFirst() : null;
                if (leg != null) legRepo.repointLegStop(leg.id(), newId);
                audit.transition(c.runId(), c.pass(), RowRef.of(row), expected, expected, "SAVINGS_CONSOLIDATE",
                        "LEG1_STOP_REPOINTED", fields("old_stop_order_id", old.orderId(), "new_stop_order_id", newId,
                                "qty", old.qty(), "stop", old.stopPrice(), "open_legs", legs.size(),
                                "leg_id", leg == null ? null : leg.id()),
                        "leg-1 stop re-placed at its old level and repointed");
                if (leg == null) {
                    legsNotSingle(row, p, c, legs.size(), "the position's stop was repointed to " + newId
                            + " but no leg was");
                }
            }
        }
        return true;
    }

    /** §8a: a leg update that could not be made is never silent. */
    void legsNotSingle(SavingsBuy row, ExecutorPosition p, Ctx c, int openLegs, String what) {
        audit.escalate(c.runId(), c.pass(), RowRef.of(row), p.symbol(), "SAVINGS_QTY_UNEXPLAINED", "WARN",
                fields("detail", "open_legs:" + openLegs, "open_legs", openLegs),
                what + " — expected exactly one OPEN leg, found " + openLegs + "; leg state is stale until fixed");
    }

    void unprotected(SavingsBuy row, String expected, ExecutorPosition p, Ctx c, String why) {
        RowRef ref = RowRef.of(row);
        if (!UNPROTECTED.equals(expected) && savingsRepo.transition(row.id(), expected, UNPROTECTED)) {
            audit.transition(c.runId(), c.pass(), ref, expected, UNPROTECTED, "SAVINGS_CONSOLIDATE",
                    "UNPROTECTED", fields("why", why), why);
        }
        audit.escalate(c.runId(), c.pass(), ref, p.symbol(), "CONSOLIDATE_UNPROTECTED", "CRITICAL",
                fields("why", why), why + " — the close-based hard trigger guards the logical stop until one stop is live again");
    }

    /** §5.3: CONSOLIDATE_CANCEL_FAILED every time; the SECOND one for the same row also raises
     *  CONSOLIDATE_STUCK (CRITICAL, once) — the add can still fill while it works. */
    void cancelFailed(SavingsBuy row, ExecutorPosition p, Ctx c, String detail) {
        int failures = audit.countFor("CONSOLIDATE_CANCEL_FAILED", row.id()) + 1;
        audit.escalate(c.runId(), c.pass(), RowRef.of(row), p.symbol(), "CONSOLIDATE_CANCEL_FAILED", "WARN",
                fields("detail", detail, "status", row.status(), "failures", failures),
                detail + " — row stays " + row.status() + ", retried next closed pass");
        if (failures >= 2 && !audit.escalatedFor("CONSOLIDATE_STUCK", row.id())) {
            audit.escalate(c.runId(), c.pass(), RowRef.of(row), p.symbol(), "CONSOLIDATE_STUCK", "CRITICAL",
                    fields("failures", failures, "detail", detail, "client_ref", row.clientRef()),
                    "the add's BUY parent is still working after its cancel in " + failures
                            + " closed passes — it can still fill; cancel it at the broker");
        }
    }

    // ---- broker helpers (each call is traced) ---------------------------------------------------

    Broker read(SavingsBuy row, ExecutorPosition p, Ctx c) {
        List<BrokerPosition> positions = audit.broker(STAGE, c.runId(), c.pass(), "positions", "row=" + row.id(),
                () -> gateway.positions(c.connection()),
                l -> l.stream().filter(x -> p.symbol().equalsIgnoreCase(x.symbol()))
                        .map(x -> "symbol=" + x.symbol() + " qty=" + plain(x.qty()) + " avg=" + plain(x.avgEntryPrice()))
                        .findFirst().orElse("symbol=" + p.symbol() + " position=none"));
        BrokerPosition bp = positions.stream().filter(x -> p.symbol().equalsIgnoreCase(x.symbol()))
                .findFirst().orElse(null);
        List<BrokerOrder> open = audit.broker(STAGE, c.runId(), c.pass(), "orders", "row=" + row.id(),
                () -> gateway.orders(c.connection()), l -> "count=" + l.size());
        List<BrokerOrder> byRef = audit.broker(STAGE, c.runId(), c.pass(), "ordersByRef",
                "row=" + row.id() + " ref=" + row.clientRef(),
                () -> gateway.ordersByRef(c.connection(), row.clientRef()),
                l -> "count=" + l.size() + " orders=" + l.stream()
                        .map(o -> o.orderId() + ":" + o.rawStatus() + ":" + plain(o.filledQty())).toList());
        return new Broker(bp, open, byRef);
    }

    boolean cancel(SavingsBuy row, ExecutorPosition p, Ctx c, String orderId, String what) {
        try {
            audit.brokerRun(STAGE, c.runId(), c.pass(), "cancelOrder",
                    "row=" + row.id() + " symbol=" + p.symbol() + " order=" + orderId + " what=" + what,
                    () -> gateway.cancelOrder(c.connection(), orderId));
            return true;
        } catch (BrokerUnavailableException e) {
            // also catches BrokerRejectedException (a subclass): a refused cancel is as much "not
            // cancelled" as an indeterminate one — the caller restores what it already cancelled
            return false;
        }
    }

    String placeStop(SavingsBuy row, ExecutorPosition p, Ctx c, BigDecimal qty, BigDecimal price, String what) {
        return audit.broker(STAGE, c.runId(), c.pass(), "placeProtectiveStop",
                "row=" + row.id() + " symbol=" + p.symbol() + " qty=" + plain(qty) + " stop=" + plain(price)
                        + " what=" + what,
                () -> gateway.placeProtectiveStop(c.connection(), p.symbol(), qty, price), id -> "order=" + id);
    }

    /** §5.2 step 6: the id is persisted at once, before any further broker call (R3 M5). */
    private void persistNewStop(SavingsBuy row, String expected, Ctx c, String id, BigDecimal price) {
        if (savingsRepo.setNewStopOrderId(row.id(), expected, id)) {
            audit.transition(c.runId(), c.pass(), RowRef.of(row), expected, expected, "SAVINGS_CONSOLIDATE",
                    "NEW_STOP_PERSISTED", fields("new_stop_order_id", id, "stop", price),
                    "new stop id persisted before any further broker call");
        } else {
            audit.escalate(c.runId(), c.pass(), RowRef.of(row), row.symbol(), "NEW_STOP_NOT_PERSISTED", "WARN",
                    fields("new_stop_order_id", id, "stop", price, "expected_status", expected, "persisted", false),
                    "the new stop " + id + " is live but its id could not be persisted (row no longer " + expected
                            + ") — reconcile cannot attribute its fill by new_stop_order_id");
        }
    }

    /** The add's BUY parent rows: by id when known, otherwise every non-stop BUY under the ref. */
    Stream<BrokerOrder> parentRows(Broker b, SavingsBuy row) {
        Stream<BrokerOrder> byId = row.entryOrderId() == null ? Stream.empty()
                : b.open().stream().filter(o -> row.entryOrderId().equals(o.orderId()));
        return Stream.concat(b.byRef().stream(), byId)
                .filter(o -> !AdoptionCandidates.isStop(o) && !AdoptionCandidates.isTakeProfit(o))
                .filter(o -> row.entryOrderId() != null ? row.entryOrderId().equals(o.orderId())
                        : AdoptionCandidates.looseSide(o, "buy"));
    }

    BrokerOrder liveParent(Broker b, SavingsBuy row) {
        return parentRows(b, row).filter(o -> AdoptionCandidates.isOpen(o) && AdoptionCandidates.isLive(o))
                .findFirst().orElse(null);
    }

    /** Every live opposite-side stop on the symbol (one OPEN row per symbol, uq_executor_position_open)
     *  — covers leg 1 and the child even when the child id is null (R2 M3). */
    List<BrokerOrder> liveSellStops(Broker b, String symbol) {
        return b.open().stream()
                .filter(o -> symbol.equalsIgnoreCase(o.symbol()) && o.orderId() != null)
                .filter(o -> AdoptionCandidates.isOpen(o) && AdoptionCandidates.isLive(o)
                        && AdoptionCandidates.isStop(o) && AdoptionCandidates.looseSide(o, "sell"))
                .toList();
    }

    BigDecimal target(BigDecimal avg, ExecutorPosition p) {
        BigDecimal t = convictionProfile.emergencyStop("BUY", avg);
        return p.trimCount() > 0 && p.activeStop() != null ? t.max(p.activeStop()) : t;
    }

    /** Fill price from the broker position, never from FILLED-only history (R3 M4). {@code fillQty} is
     *  the GROSS add fill: a sale (the window stop) leaves an average-cost basis unchanged, so the
     *  average covers qty_before + fillQty shares — without a window stop that is bp.qty. */
    static BigDecimal derivedFillPrice(BigDecimal avg, BigDecimal fillQty, SavingsBuy row) {
        if (avg == null || fillQty == null || fillQty.signum() <= 0) return null;
        return avg.multiply(row.qtyBefore().add(fillQty)).subtract(row.avgBefore().multiply(row.qtyBefore()))
                .divide(fillQty, 6, RoundingMode.HALF_UP);
    }

    static BigDecimal refund(SavingsBuy row, BigDecimal unfilledQty) {
        if (row.limitEur() == null || unfilledQty == null || unfilledQty.signum() <= 0) return BigDecimal.ZERO;
        return unfilledQty.multiply(row.limitEur()).setScale(6, RoundingMode.HALF_UP);
    }

    BigDecimal close(String symbol) {
        ExecutorIndicators.Levels lv = indicators.levels(symbol, atrPeriod, swingPeriod);
        return lv != null && lv.available() ? lv.referencePrice() : null;
    }

    /** Comma-joined order ids — never a {@code List.toString()}, whose spaces would surface as
     *  stray {@code _} in the key=value line. */
    private static String ids(List<BrokerOrder> orders) {
        return String.join(",", orders.stream().map(BrokerOrder::orderId).toList());
    }
}
