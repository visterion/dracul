package de.visterion.dracul.executor;

import de.visterion.dracul.executor.SavingsPlanAudit.RowRef;
import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.ExecutionGateway;
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

import static de.visterion.dracul.executor.SavingsBuy.CONSOLIDATED;
import static de.visterion.dracul.executor.SavingsBuy.CONSOLIDATING;
import static de.visterion.dracul.executor.SavingsBuy.EMERGENCY_EXIT;
import static de.visterion.dracul.executor.SavingsBuy.EXPIRED;
import static de.visterion.dracul.executor.SavingsBuy.PLACED;
import static de.visterion.dracul.executor.SavingsBuy.PLACING;
import static de.visterion.dracul.executor.SavingsBuy.REJECTED;
import static de.visterion.dracul.executor.SavingsBuy.UNPROTECTED;
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
        if (EMERGENCY_EXIT.equals(row.status())) return "unchanged";        // Task 9: the D8 flatten step
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
        BigDecimal fillQty = bpQty.subtract(row.qtyBefore());
        if (fillQty.signum() <= 0) return expire(row, p, b, c);
        return consolidateFilled(row, p, b, c, bpQty, fillQty, null);
    }

    String consolidateFilled(SavingsBuy row, ExecutorPosition p, Broker b, Ctx c, BigDecimal bpQty,
            BigDecimal fillQty, BigDecimal fillPriceOverride) {
        BigDecimal avg = b.avg();
        BigDecimal fillPrice = fillPriceOverride != null ? fillPriceOverride : derivedFillPrice(avg, bpQty, row);
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

    void cancelFailed(SavingsBuy row, ExecutorPosition p, Ctx c, String detail) {
        audit.escalate(c.runId(), c.pass(), RowRef.of(row), p.symbol(), "CONSOLIDATE_CANCEL_FAILED", "WARN",
                fields("detail", detail, "status", row.status()),
                detail + " — row stays " + row.status() + ", retried next closed pass");
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

    /** Fill price from the broker position, never from FILLED-only history (R3 M4). */
    static BigDecimal derivedFillPrice(BigDecimal avg, BigDecimal bpQty, SavingsBuy row) {
        BigDecimal fillQty = bpQty.subtract(row.qtyBefore());
        if (avg == null || fillQty.signum() <= 0) return null;
        return avg.multiply(bpQty).subtract(row.avgBefore().multiply(row.qtyBefore()))
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

    ExecutorPositionLeg singleOpenLeg(long positionId) {
        List<ExecutorPositionLeg> legs = legRepo.findOpenByPosition(positionId);
        return legs.size() == 1 ? legs.getFirst() : null;
    }

    /** Comma-joined order ids — never a {@code List.toString()}, whose spaces would surface as
     *  stray {@code _} in the key=value line. */
    private static String ids(List<BrokerOrder> orders) {
        return String.join(",", orders.stream().map(BrokerOrder::orderId).toList());
    }
}
