package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerClosedPosition;
import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.executor.broker.OrderRole;
import de.visterion.dracul.executor.broker.OrderStatus;
import de.visterion.dracul.notify.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciles the executor's position book against the broker's actual state: detects
 * stop/target fills (or positions that simply disappeared) and closes them in the book,
 * and otherwise ratchets highest-price/MFE-R bookkeeping for still-open positions.
 *
 * <p>On {@link BrokerUnavailableException} this deliberately does nothing to the book —
 * a transient broker outage must never be mistaken for positions closing.
 *
 * <p><b>Multi-leg reconciliation (BUG-S11).</b> The broker holds each tranche of a position as
 * its own position with its own stop order. The book mirrors that in {@code executor_position_leg}
 * ({@link ExecutorPositionLeg}): one OPEN leg per live tranche, while {@code executor_position}
 * stays the aggregate row every other consumer (MAX_POSITIONS, heat, cooldowns, outcome_log)
 * reads. Reconcile therefore evaluates a position against its OPEN legs, not against a single
 * bracket id. Before matching anything it converges each OPEN leg's {@code qty} to the broker's
 * own number whenever that leg's stop order is still reported WORKING at a different size —
 * {@code qty} means shares HELD, never shares intended, exactly as for the position row.
 *
 * <p>Four findings, evaluated in this order:
 * <ol>
 *   <li><b>Every OPEN leg has an observed fill</b> → the whole position exited. Each leg is
 *       CLOSED with its own fill, and the row is closed through the normal close path with a
 *       quantity-weighted exit price and the exit reason the legs themselves report
 *       (HARD_STOP / TAKE_PROFIT), so cooldown, notification and outcome_log are unchanged.</li>
 *   <li><b>Some legs have a fill</b> → the position shrank on its own. The filled legs are
 *       CLOSED, the row is TRIMmed to the surviving legs' quantity (staying OPEN) and a TRIM
 *       decision is written in the same shape as the webhook partial-exit path, so
 *       {@code OutcomeBatchJob}'s weighted realized R still counts it. The filled tranche's
 *       stop-leg column is nulled and, when that leaves a two-tranche row naming a single leg,
 *       the collapse is recorded — otherwise the ratchet escalates on every run over a state this
 *       trim created. An INFO Telegram note goes out too: a position that shrank without an
 *       instruction is worth knowing about.</li>
 *   <li><b>The broker no longer reports the position and no fill was observed</b> → the legs are
 *       CLOSED as {@code RECONCILE_GONE} and the row takes the unchanged RECONCILE_GONE close
 *       path (which still recovers real fills from {@code closedPositions}).</li>
 *   <li><b>The broker still holds the symbol but at a quantity the legs do not account for, and
 *       no fill explains the difference</b> → {@code ESCALATE LEG_QTY_DESYNC}, carrying book,
 *       leg and broker quantities in the {@code inputs_snapshot}, row left OPEN. This is the
 *       only remaining case that needs an operator. One shortfall is exempt because it IS
 *       attributable: a single open leg holding more than the broker syncs down through the
 *       ordinary {@code QTY_SYNC} path instead.</li>
 * </ol>
 * Anything else is an ordinary still-open position and falls through to
 * {@link #updateMaintenance}. No path here closes a position on a guess: it takes either an
 * observed fill or the broker's confirmed disappearance of the position.
 *
 * <p><b>A stop fill no leg claims is never silent</b> ({@link #unclaimedStopFills}). A tranche
 * whose entry and whose stop both fill between two nightly passes never becomes a leg — seeding
 * only ever sees a WORKING stop — and {@link #matchLegFills} iterates existing legs, so that fill
 * would be looked at by nothing. The surviving legs then agree with the broker and the pass would
 * end at {@link #updateMaintenance} with no TRIM, no realized R and no alarm for shares that
 * demonstrably left; before the leg rewrite the same state raised {@code TRANCHE2_DESYNC}. A
 * FILLED order carrying one of the position's OWN stop-order ids that no OPEN leg claimed
 * therefore escalates {@code UNCLAIMED_STOP_FILL}. The escalation is written whatever else the
 * pass decides (the extra fill is worth knowing about even while the position closes), and where
 * the position would otherwise have stayed open it is left completely untouched instead — every
 * quantity below that point is computed from legs the evidence has just contradicted.
 *
 * <p><b>Missing fill history is not evidence of absence.</b> {@code filledOrdersSince} is fetched
 * once per pass and fails soft — a failure never aborts reconcile — but finding 3, the
 * one-shortfall exemption above, and {@link #syncLegQuantities}'s per-leg WORKING-stop
 * convergence all reason from "no fill was observed" (directly, or indirectly: a WORKING stop
 * reporting a smaller qty than the leg's is exactly what an unobserved partial fill looks like),
 * and that reasoning is only sound when the history call actually ran. When it throws,
 * {@code fillHistoryAvailable} is false for the rest of the pass and: (1) {@link
 * #syncLegQuantities} skips its per-leg convergence entirely rather than risk pre-resizing a leg
 * on a guess and erasing the shortfall the next two checks need to see; (2) every position that
 * would otherwise be CLOSED as {@code RECONCILE_GONE} is left OPEN with its legs untouched
 * instead; (3) the one-shortfall exemption's sync is withheld the same way. All three record an
 * {@code ESCALATE FILL_HISTORY_UNAVAILABLE} row ({@link #escalateMissingEvidence}) carrying a
 * {@code withheld} discriminator (RECONCILE_GONE vs. QTY_SYNC_SHORTFALL) plus the book/leg/broker
 * quantities in {@code inputs_snapshot}, so the distinction is queryable, not just prose. The
 * legless chain applies the identical rule to its own RECONCILE_GONE branch and to the
 * quantity-shrink case {@link #updateMaintenance} would otherwise sync unconditionally (see
 * {@link #legacyBrokerQtyShrank}). An empty but successfully-fetched history is unaffected — that
 * is a fact about the world, not a failure.
 *
 * <p>Because finding 1 closes on fills alone, the orphan scan runs a second time after the loop,
 * against the survivors: a holding the broker still reports for a row that just closed is flagged
 * in the same pass rather than a full cycle later.
 *
 * <p><b>An interrupted close finishes itself</b> ({@link #completeInterruptedClose}). Nothing in
 * the executor is transactional, and a crash between the leg loop and {@link #bookClose} would
 * otherwise leave the legs CLOSED under an OPEN row — a state that escalates
 * {@code TRANCHE2_DESYNC} on every run forever and can never be re-legged, because a CLOSED leg
 * still occupies its tranche. An OPEN row whose legs are all terminal is therefore booked closed
 * from what those legs already recorded, before the legless chain below is reached.
 *
 * <p>A position with no leg rows at all (placed before the leg table existed and not covered by
 * its backfill) keeps the previous single-row behaviour verbatim, including the
 * {@code TRANCHE2_DESYNC} escalation for a two-bracket row — with no legs there is nothing to
 * reconcile against, and inventing one would be exactly the guess this class must not make.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class ReconcileService {

    /** {@code reasonCode}s that {@link HardTriggerService} produces — kept in sync with its
     *  {@code Trigger} reason codes. Drives the LOG_HARD_EXIT-vs-RECONCILE_CLOSE action choice
     *  when finalizing a pending-exit row ({@link #finalizePendingExitOrKeep}): a hard-trigger
     *  origin keeps the same action as its submit-time decision row, anything else (e.g. a
     *  webhook soft/LLM exit reason) is a RECONCILE_CLOSE. */
    private static final Set<String> HARD_REASONS =
            Set.of("HARD_STOP", "HARD_KILL_CRITERIA", "GIVEBACK_BREACH");

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    /** How far back to ask for filled orders. Reconcile runs nightly, so this only has to span
     *  the gap between two passes plus slack; 72h covers a weekend and a missed run without
     *  pulling an unbounded history window on every call. */
    private static final int FILL_LOOKBACK_HOURS = 72;

    private final ExecutionGateway gateway;
    private final ExecutorPositionRepository positionRepo;
    private final DecisionLogRepository decisionRepo;
    private final CooldownRepository cooldownRepo;
    private final RuleVersionProvider ruleVersions;
    private final ObjectMapper mapper;
    private final TelegramNotifier telegram;
    private final ExecutorNotifier executorNotifier;
    private final int cooldownDays;
    private final int pendingExitStaleHours;
    private final ExecutorPositionLegRepository legRepo;
    private final Clock clock;

    @Autowired
    public ReconcileService(
            ExecutionGateway gateway,
            ExecutorPositionRepository positionRepo,
            DecisionLogRepository decisionRepo,
            CooldownRepository cooldownRepo,
            RuleVersionProvider ruleVersions,
            ObjectMapper mapper,
            TelegramNotifier telegram,
            ExecutorNotifier executorNotifier,
            @Value("${dracul.executor.cooldown-days:10}") int cooldownDays,
            @Value("${dracul.executor.pending-exit-stale-hours:24}") int pendingExitStaleHours,
            ExecutorPositionLegRepository legRepo) {
        this(gateway, positionRepo, decisionRepo, cooldownRepo, ruleVersions, mapper, telegram,
                executorNotifier, cooldownDays, pendingExitStaleHours, legRepo, Clock.systemUTC());
    }

    ReconcileService(
            ExecutionGateway gateway,
            ExecutorPositionRepository positionRepo,
            DecisionLogRepository decisionRepo,
            CooldownRepository cooldownRepo,
            RuleVersionProvider ruleVersions,
            ObjectMapper mapper,
            TelegramNotifier telegram,
            ExecutorNotifier executorNotifier,
            int cooldownDays,
            int pendingExitStaleHours,
            ExecutorPositionLegRepository legRepo,
            Clock clock) {
        this.gateway = gateway;
        this.positionRepo = positionRepo;
        this.decisionRepo = decisionRepo;
        this.cooldownRepo = cooldownRepo;
        this.ruleVersions = ruleVersions;
        this.mapper = mapper;
        this.telegram = telegram;
        this.executorNotifier = executorNotifier;
        this.cooldownDays = cooldownDays;
        this.pendingExitStaleHours = pendingExitStaleHours;
        this.legRepo = legRepo;
        this.clock = clock;
    }

    /**
     * Result of one reconcile pass: the still-open {@code survivors}, plus the subset of their
     * ids whose GTD limit ENTRY is still working at the broker with no position held
     * ({@code unfilledIds}). Unfilled entries have no broker holdings, so downstream hard
     * triggers / stop ratcheting must not act on them — flattening a position that was never
     * filled would either escalate spuriously or fabricate a CLOSED row with a made-up
     * realized R (and a cooldown) while the still-WORKING entry order stays live.
     * {@link EntryExpiryService} remains the lifecycle owner of unfilled entries.
     */
    public record ReconcileResult(List<ExecutorPosition> survivors, Set<Long> unfilledIds) {}

    public ReconcileResult reconcile(String connection, String runId) {
        List<ExecutorPosition> open = positionRepo.findOpen().stream()
                .filter(p -> connection.equals(p.connection()))
                .toList();

        List<BrokerPosition> brokerPositions;
        List<BrokerOrder> orders;
        try {
            brokerPositions = gateway.positions(connection);
            orders = gateway.orders(connection);
        } catch (BrokerRejectedException e) {
            // A rejection is a VERDICT. BrokerRejectedException extends BrokerUnavailableException
            // so that every pre-existing catch keeps compiling, which also meant this one filed
            // the broker's explicit "no" as BROKER_UNAVAILABLE -- the one thing this branch
            // defines that code as never meaning (no verdict at all). Agora's wire code goes into
            // a queryable reject_code field and into the prose, the same shape HardTriggerService,
            // ExecutorWebhookController and EntryExpiryService use.
            ObjectNode rejectInputs = mapper.createObjectNode();
            rejectInputs.put("reject_code", e.rejectCode());
            decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                    "MAINTENANCE", null, null, null, null, rejectInputs, null,
                    "ESCALATE", "BROKER_REJECTED", null,
                    "broker rejected the position/order read during reconcile ["
                            + (e.rejectCode() == null ? "no reject code" : e.rejectCode())
                            + "]: " + e.getMessage(), null, null, null));
            // Same book-untouched outcome as an outage: without the broker's state there is
            // nothing to reconcile against, and no id is flagged unfilled.
            return new ReconcileResult(open, Set.of());
        } catch (BrokerUnavailableException e) {
            decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                    "MAINTENANCE", null, null, null, null, null, null,
                    "ESCALATE", "BROKER_UNAVAILABLE", null,
                    "broker unavailable during reconcile: " + e.getMessage(), null, null, null));
            // Fill state is unknown -> no ids flagged unfilled; downstream hard triggers stay
            // safe regardless, because any flatten attempt hits the same broker outage and
            // escalates without touching the book.
            return new ReconcileResult(open, Set.of());
        }

        // Recently FILLED orders, fetched separately because `orders` above is an OPEN-orders
        // view on every broker (Saxo /port/v1/orders/me, Alpaca's status=open default) and can
        // therefore never contain a fill. Without this, findFilledExitLeg below was unreachable
        // and a stopped-out position could only ever be booked as RECONCILE_GONE, losing the
        // HARD_STOP / TAKE_PROFIT distinction that drives cooldowns and the outcome book.
        //
        // Deliberately fail-soft and NOT part of the BROKER_UNAVAILABLE bail-out above: the
        // history endpoint is a separate, slower call, and losing it must not abort the whole
        // pass. It must also not be treated as "no fills happened" for anything that would use
        // that absence to CLOSE or RESIZE the book, though: an empty filledOrders list is
        // ambiguous between "the broker genuinely reports nothing" and "we could not ask", and
        // only the former licenses the RECONCILE_GONE close / QTY_SYNC-from-shortfall paths.
        // fillHistoryAvailable carries that distinction down into reconcileByLegs and the legless
        // chain below, so a broken evidence channel escalates (FILL_HISTORY_UNAVAILABLE) instead
        // of quietly booking an estimate or rewriting a quantity.
        boolean fillHistoryAvailable = true;
        List<BrokerOrder> filledOrders;
        try {
            filledOrders = gateway.filledOrdersSince(connection,
                    clock.instant().minus(Duration.ofHours(FILL_LOOKBACK_HOURS)));
        } catch (RuntimeException e) {
            log.warn("filled-order history unavailable during reconcile ({}); "
                    + "positions that vanished stay OPEN this pass", e.getMessage());
            filledOrders = List.of();
            fillHistoryAvailable = false;
        }

        // Orphan scan (broker→DB): a live broker position with no open book row means an
        // entry was placed but never persisted (crash / DB failure after placeBracket) —
        // unmanaged capital. Escalate only; NEVER auto-flatten (operator-in-the-loop). Runs on
        // the pre-loop `open` list, so a position about to be closed this run is still "known"
        // (no false orphan) — the second pass below covers the ones that actually did close.
        Set<String> orphansReported = new HashSet<>();
        escalateOrphans(brokerPositions, open, orphansReported, runId,
                "has no open book row — unmanaged capital, operator attention required");

        List<ExecutorPosition> survivors = new ArrayList<>();
        Set<Long> unfilledIds = new HashSet<>();
        for (ExecutorPosition p : open) {
            BrokerPosition bp = brokerPositions.stream()
                    .filter(x -> x.symbol().equals(p.symbol()))
                    .findFirst().orElse(null);

            // A hard-trigger flatten or fill-less webhook FULL exit already submitted an order
            // for this position but has not yet been confirmed — branch here FIRST, before any
            // other reconcile logic (tranche2 desync, entry-pending, normal fill detection) can
            // touch it. Never close on our own say-so; only the broker's confirmed state may.
            if (p.pendingExitReason() != null) {
                finalizePendingExitOrKeep(p, bp, orders, filledOrders, runId, survivors);
                continue;
            }

            // Learn the legs the broker actually holds BEFORE routing. Nothing else in
            // production writes a leg row, so without this every position opened after the leg
            // table shipped would stay legless forever and fall through to the legacy escalating
            // path below.
            seedLegsFromWorkingStops(p, bp, orders, runId);

            List<ExecutorPositionLeg> legs = legRepo.findOpenByPosition(p.id());
            if (!legs.isEmpty()) {
                reconcileByLegs(p, legs, bp, orders, filledOrders, connection, runId,
                        survivors, unfilledIds, fillHistoryAvailable);
                continue;
            }

            // An OPEN row whose legs are ALL closed is a close that was interrupted between
            // closeLeg and bookClose. Finish it here, or it is stuck forever (see the method).
            if (completeInterruptedClose(p, bp, connection, runId)) {
                continue;
            }

            // No leg rows for this position -> the pre-leg single-row behaviour, unchanged.
            BrokerOrder filledLeg = findFilledExitLeg(p, filledOrders);

            if (p.tranche2OrderId() != null && (bp == null || filledLeg != null)) {
                escalateTranche2Desync(p, filledLeg, runId);
                survivors.add(p);
            } else if (bp == null && filledLeg == null && entryStillPending(p, orders)) {
                // No broker position exists because the GTD limit ENTRY is still working (or only
                // partially filled) — this is NOT "position gone", so it must not be closed as
                // RECONCILE_GONE. EntryExpiryService owns this lifecycle now (cancel after
                // entry-gtd-days); keep the row OPEN and untouched here, and flag it unfilled so
                // the pipeline keeps hard triggers / ratcheting off it (no broker holdings).
                survivors.add(p);
                unfilledIds.add(p.id());
            } else if (bp == null && filledLeg == null && !fillHistoryAvailable) {
                // The broker no longer reports the position, but with the fill history broken we
                // cannot tell "genuinely gone" apart from "we could not ask" -- and RECONCILE_GONE
                // would book an estimated exit for a position we have no real evidence closed at
                // all. Missing evidence is not evidence of absence: leave the row OPEN and let an
                // operator look, rather than have it silently degrade into the estimate.
                escalateMissingEvidence(p, runId, "broker no longer reports the position",
                        "RECONCILE_GONE", p.qty(), null, null);
                survivors.add(p);
            } else if (bp == null || filledLeg != null) {
                closePosition(p, filledLeg, bp, connection, runId);
            } else if (!fillHistoryAvailable && legacyBrokerQtyShrank(p, bp)) {
                // Symmetric to the leg path's one-shortfall gate: updateMaintenance below would
                // otherwise sync p.qty() down to the broker's smaller number unconditionally and
                // book a bare QTY_SYNC row -- no TRIM, no realized R for the missing shares. With
                // the fill history broken that shortfall cannot be told apart from an unobserved
                // stop fill, so it escalates instead of guessing.
                escalateMissingEvidence(p, runId,
                        "broker reports fewer shares than the book accounts for, with no fill "
                                + "observed to explain it",
                        "QTY_SYNC_SHORTFALL", p.qty(), null, bp.qty());
                survivors.add(p);
            } else {
                survivors.add(updateMaintenance(p, bp, runId));
            }
        }

        // Second orphan pass, against the SURVIVORS: a row this pass closed while the broker still
        // reported a holding leaves shares nobody is managing. Closing on observed fills is right
        // — requiring the position feed to agree would push a genuinely stopped-out position back
        // into an escalation whenever that feed lags the fill feed by one poll, which is the
        // original bug — but the leftover has to be reported in THIS pass. Without this it is only
        // seen a full cycle later, once the closed row has dropped out of findOpen().
        escalateOrphans(brokerPositions, survivors, orphansReported, runId,
                "is still reported by the broker after its book row was closed this run — "
                        + "unmanaged capital, verify the holding is really gone");

        return new ReconcileResult(survivors, unfilledIds);
    }

    /** Escalates every broker holding that no position in {@code known} accounts for, skipping
     *  symbols already reported in {@code reported} (and adding the ones it reports to it), so the
     *  pre-loop and post-loop passes can never alarm twice on the same symbol. */
    private void escalateOrphans(List<BrokerPosition> brokerPositions, List<ExecutorPosition> known,
            Set<String> reported, String runId, String detail) {
        for (BrokerPosition bp : brokerPositions) {
            if (reported.contains(bp.symbol())) continue;
            if (known.stream().anyMatch(p -> p.symbol().equals(bp.symbol()))) continue;
            reported.add(bp.symbol());
            decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                    "MAINTENANCE", null, null, null, bp.symbol(), null, null,
                    "ESCALATE", "ORPHAN_POSITION", null,
                    "broker position " + bp.symbol() + " " + detail, null, null, null));
            telegram.notifyAlert(bp.symbol(), "ORPHAN_POSITION", "CRITICAL",
                    "broker holds " + bp.symbol() + " with no executor book row — check ORPHANED_ORDER decisions / reconcile manually");
        }
    }

    /** True while the position's ENTRY order ({@code brokerOrderId}) is still reported by the
     *  broker as WORKING or PARTIALLY_FILLED — i.e. no (full) fill has produced a broker position
     *  yet. Matched by orderId (the entry IS the bracket parent, unlike exit legs which are
     *  matched via parentId in {@link #matchesPosition}). */
    private boolean entryStillPending(ExecutorPosition p, List<BrokerOrder> orders) {
        if (p.brokerOrderId() == null) return false;
        return orders.stream()
                .filter(o -> p.brokerOrderId().equals(o.orderId()))
                .anyMatch(o -> o.status() == OrderStatus.WORKING
                        || o.status() == OrderStatus.PARTIALLY_FILLED);
    }

    /** True when the broker holds fewer shares than a legless (single-row) position's book qty.
     *  The legacy-chain counterpart of {@link #brokerShortfallAttributableToOneLeg}: with no legs
     *  to attribute the shortfall to, the single row IS the position, so any smaller broker
     *  quantity is unambiguously that row's -- ordinarily synced through {@link #updateMaintenance}.
     *  Used only to gate that sync behind {@code fillHistoryAvailable}; it does not by itself
     *  decide anything. */
    private boolean legacyBrokerQtyShrank(ExecutorPosition p, BrokerPosition bp) {
        BigDecimal brokerQty = bp.qty();
        if (brokerQty == null || brokerQty.signum() <= 0 || p.qty() == null) return false;
        return brokerQty.compareTo(p.qty()) < 0;
    }

    /**
     * The position's stop/target leg that has FILLED, or null. {@code filledOrders} must be the
     * history-backed list — the open-orders view can never contain a fill.
     *
     * <p>The role filter is deliberately skipped for an order whose id IS one of the stop legs we
     * persisted ({@code stop_order_id} / {@code tranche2_stop_order_id}): that identity is our own
     * record of what the leg is, and it beats the broker's reported role. It has to, because the
     * history endpoint carries no bracket-leg structure — Agora falls back to deriving the role
     * from the order type, and an order that came back as OTHER would otherwise be dropped and
     * the fill silently missed. Only orders matched purely by {@code parentId} still need a
     * STOP_LOSS/TAKE_PROFIT role, since a parent match alone does not say which leg this is.
     */
    private BrokerOrder findFilledExitLeg(ExecutorPosition p, List<BrokerOrder> filledOrders) {
        return filledOrders.stream()
                .filter(o -> o.status() == OrderStatus.FILLED)
                .filter(o -> matchesKnownStopLeg(p, o)
                        || ((o.role() == OrderRole.STOP_LOSS || o.role() == OrderRole.TAKE_PROFIT)
                            && matchesPosition(p, o)))
                .map(o -> asStopLegIfKnown(p, o))
                .findFirst().orElse(null);
    }

    /** A known stop leg reported with a vague role (the history endpoint's best-effort
     *  {@code roleOf(type)} hint) is relabelled STOP_LOSS, so {@link #closePosition} books it as
     *  HARD_STOP instead of falling through to the RECONCILE_GONE estimate. An explicit
     *  TAKE_PROFIT is never overwritten. */
    private BrokerOrder asStopLegIfKnown(ExecutorPosition p, BrokerOrder o) {
        if (o.role() == OrderRole.STOP_LOSS || o.role() == OrderRole.TAKE_PROFIT) return o;
        if (!matchesKnownStopLeg(p, o)) return o;
        return new BrokerOrder(o.orderId(), o.clientRef(), o.symbol(), OrderRole.STOP_LOSS,
                o.status(), o.qty(), o.filledQty(), o.avgFillPrice(), o.parentId());
    }

    /** True when the order IS one of the two stop legs this position recorded at placement. */
    private boolean matchesKnownStopLeg(ExecutorPosition p, BrokerOrder o) {
        boolean stopIdMatch = p.stopOrderId() != null && p.stopOrderId().equals(o.orderId());
        boolean stop2Match = p.tranche2StopOrderId() != null && p.tranche2StopOrderId().equals(o.orderId());
        return stopIdMatch || stop2Match;
    }

    private boolean matchesPosition(ExecutorPosition p, BrokerOrder o) {
        boolean parentMatch = p.brokerOrderId() != null && p.brokerOrderId().equals(o.parentId());
        boolean parent2Match = p.tranche2OrderId() != null && p.tranche2OrderId().equals(o.parentId());
        return parentMatch || parent2Match || matchesKnownStopLeg(p, o);
    }

    /**
     * v1 tranche-2 desync handling (see class javadoc): a filled/vanished exit leg on a
     * two-bracket position cannot be safely reconciled to a single book row, so this records an
     * escalation and leaves the row untouched (still OPEN) rather than closing or silently
     * ignoring it.
     */
    private void escalateTranche2Desync(ExecutorPosition p, BrokerOrder filledLeg, String runId) {
        String legDescription;
        if (filledLeg == null) {
            legDescription = "position vanished from broker";
        } else {
            boolean isTranche2Leg = p.tranche2OrderId() != null && p.tranche2OrderId().equals(filledLeg.parentId())
                    || p.tranche2StopOrderId() != null && p.tranche2StopOrderId().equals(filledLeg.orderId());
            legDescription = (isTranche2Leg ? "tranche-2 " : "tranche-1 ") + filledLeg.role() + " leg filled";
        }

        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("tranche2_order_id", p.tranche2OrderId());
        inputs.put("tranche2_stop_order_id", p.tranche2StopOrderId());

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                "ESCALATE", "TRANCHE2_DESYNC", null,
                "position " + p.symbol() + " (id " + p.id() + "): " + legDescription
                        + " — TRANCHE2_DESYNC — operator attention required", null, null, null));
    }

    // ===========================================================================================
    // Leg-based reconciliation (see class javadoc)
    // ===========================================================================================

    /** One OPEN leg together with the broker fill that closed it and the exit reason that fill
     *  stands for. {@code qty} is deliberately the LEG's quantity, not the order's: the leg row is
     *  the book's record of how many shares this tranche held, and it is those shares that leave
     *  the book. */
    private record LegFill(ExecutorPositionLeg leg, BrokerOrder order, String exitReason) {
        BigDecimal qty() { return leg.qty(); }
        BigDecimal price() { return order.avgFillPrice(); }
    }

    /**
     * Reconciles one position against its OPEN legs. Quantities are converged to the broker
     * first (unless {@code fillHistoryAvailable} is false, in which case that convergence is
     * skipped entirely — see {@link #syncLegQuantities}), then the four findings of the class
     * javadoc are evaluated in order.
     */
    private void reconcileByLegs(ExecutorPosition p, List<ExecutorPositionLeg> legs,
            BrokerPosition bp, List<BrokerOrder> openOrders, List<BrokerOrder> filledOrders,
            String connection, String runId, List<ExecutorPosition> survivors,
            Set<Long> unfilledIds, boolean fillHistoryAvailable) {
        legs = syncLegQuantities(p, legs, openOrders, runId, fillHistoryAvailable);
        List<LegFill> fills = matchLegFills(legs, filledOrders);
        BigDecimal legsQty = legs.stream().map(ExecutorPositionLeg::qty)
                .filter(q -> q != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BrokerOrder> unclaimedStopFills = unclaimedStopFills(p, filledOrders, fills);
        if (!unclaimedStopFills.isEmpty()) {
            escalateUnclaimedStopFill(p, legs, unclaimedStopFills, bp, runId);
        }

        if (!fills.isEmpty() && fills.size() == legs.size()) {
            closePositionFromLegs(p, fills, bp, connection, runId);
        } else if (!fills.isEmpty()) {
            survivors.add(trimToSurvivingLegs(p, legs, fills, runId));
        } else if (bp == null && entryStillPending(p, openOrders)) {
            // The GTD limit ENTRY is still working, so "no broker position" is not "position
            // gone". EntryExpiryService owns this lifecycle; keep the row untouched and flag it
            // unfilled so hard triggers / ratcheting stay off it.
            survivors.add(p);
            unfilledIds.add(p.id());
        } else if (bp == null && !fillHistoryAvailable) {
            // Same evidence gap as the legless chain: the broker no longer reports the position,
            // but with the fill history broken "no fill was observed" cannot be told apart from
            // "we could not ask". Booking RECONCILE_GONE here would close every open leg on an
            // estimate for shares whose fate is genuinely unknown this pass. Leave the row (and
            // its legs) OPEN and escalate instead.
            escalateMissingEvidence(p, runId, "broker no longer reports the position",
                    "RECONCILE_GONE", p.qty(), legsQty, null);
            survivors.add(p);
        } else if (bp == null) {
            // Confirmed disappearance with no observed fill: same RECONCILE_GONE close as a
            // single-tranche position, with every leg booked out alongside the row. The exit is
            // resolved first so the legs carry whatever price that path recovered from the
            // broker's closed positions, rather than a null the row does not have.
            ResolvedExit resolved = resolveExit(p, null, null, connection);
            Instant closedAt = clock.instant();
            for (ExecutorPositionLeg leg : legs) {
                legRepo.closeLeg(leg.id(), resolved.exitPrice(), resolved.exitReason(), closedAt);
            }
            bookClose(p, resolved, connection, runId);
        } else if (!unclaimedStopFills.isEmpty()) {
            // The broker still holds the symbol and a stop order THIS position placed filled, but
            // no OPEN leg claimed it -- so the shares behind that fill left without a TRIM, a
            // realized R, or any other record. Everything below would write the book on the
            // assumption that the legs account for the holding, and updateMaintenance in
            // particular would sync p.qty() down to the surviving legs' number: a quantity
            // rewritten on evidence that has just been contradicted. Leave the row exactly as it
            // is; the escalation above is already recorded.
            survivors.add(p);
        } else if (!fillHistoryAvailable && brokerShortfallAttributableToOneLeg(legs, bp)) {
            // The one-leg-shortfall fallback below exists precisely because a smaller broker
            // quantity IS attributable to the single open leg -- an unobserved stop fill. That
            // attribution depends on "no fill was observed" being a fact, not a guess born from a
            // broken evidence channel. With the fill history unavailable this pass, a smaller
            // broker holding could just as well be a stop fill we failed to see, and syncing the
            // qty down here would silently converge the book with no TRIM row and no realized R
            // for those shares. Escalate instead of guessing which one it is.
            escalateMissingEvidence(p, runId,
                    "broker reports fewer shares than the open leg(s) account for, with no fill "
                            + "observed to explain it",
                    "QTY_SYNC_SHORTFALL", p.qty(), legsQty, bp.qty());
            survivors.add(p);
        } else if (brokerShortfallAttributableToOneLeg(legs, bp)) {
            survivors.add(syncSingleLegDown(p, legs.getFirst(), bp, runId));
        } else if (brokerQtyUnaccountedFor(legs, bp)) {
            escalateLegQtyDesync(p, legs, bp, runId);
            survivors.add(p);
        } else {
            survivors.add(updateMaintenance(p, bp, runId));
        }
    }

    /**
     * Finishes a close that was interrupted after its legs were booked out but before the row
     * was: an OPEN position whose legs are ALL terminal, at least one of them CLOSED.
     *
     * <p><b>Why this exists.</b> There is no transactional boundary anywhere in the executor, and
     * this branch introduced its first cross-table invariant. A crash between the
     * {@link ExecutorPositionLegRepository#closeLeg} loop and {@link #bookClose} in
     * {@link #closePositionFromLegs} leaves the legs CLOSED and the row OPEN. Without this method
     * that state is PERMANENT: the next pass finds no OPEN leg, falls to the legacy legless chain
     * and escalates {@code TRANCHE2_DESYNC} on every run forever, and it can never be re-legged
     * either, because a CLOSED leg still occupies its tranche.
     *
     * <p><b>Why completion and not re-seeding.</b> Re-seeding was the obvious repair, and it does
     * not work: {@link #seedLegsFromWorkingStops} returns immediately when the broker does not
     * report the position ({@code bp == null}), and a position that stopped out — the only way to
     * reach {@link #closePositionFromLegs} at all — is exactly a position the broker no longer
     * reports. Seeding never gets as far as looking at which tranches are taken, so relaxing that
     * check would repair nothing. Resurrecting a CLOSED leg would also overwrite a booked exit
     * record, which is evidence, with a guess.
     *
     * <p><b>Nothing is invented.</b> The legs already carry what the interrupted close had
     * resolved. Their quantity-weighted exit price is booked as {@code FILL} only when every
     * closed leg carries a positive price AND a fill-derived reason (HARD_STOP / TAKE_PROFIT) —
     * those two together mean the price came from the broker's own {@code avgFillPrice} through
     * {@link #matchLegFills}. Anything else (a RECONCILE_GONE-reasoned leg, a leg the price
     * fallback left without one) goes through {@link #resolveExit} instead, which re-derives the
     * exit honestly and labels its own provenance. Legs that are merely CANCELLED book nothing:
     * those shares were never held, so there is no close to complete.
     *
     * @return true when the row was closed, so the caller must not treat it as a survivor
     */
    private boolean completeInterruptedClose(ExecutorPosition p, BrokerPosition bp,
            String connection, String runId) {
        List<ExecutorPositionLeg> all = legRepo.findByPosition(p.id());
        // A legless position is the ordinary pre-leg case, not an interrupted close.
        if (all.isEmpty()) return false;
        List<ExecutorPositionLeg> closed = all.stream()
                .filter(l -> ExecutorPositionLeg.CLOSED.equals(l.status()))
                .toList();
        if (closed.isEmpty()) return false;

        boolean everyLegFillDerived = closed.stream().allMatch(leg ->
                leg.exitPrice() != null && leg.exitPrice().signum() > 0
                        && leg.qty() != null && leg.qty().signum() > 0
                        && ("HARD_STOP".equals(leg.exitReason())
                            || "TAKE_PROFIT".equals(leg.exitReason())));

        log.warn("position {} (id {}): OPEN row with {} closed leg(s) and no open leg — a close "
                + "was interrupted after its legs were booked out; completing it now",
                p.symbol(), p.id(), closed.size());

        if (!everyLegFillDerived) {
            // At least one leg's price/reason is not broker-fill provenance, so the row cannot
            // claim FILL. resolveExit re-derives the exit and labels where its price came from.
            bookClose(p, resolveExit(p, null, bp, connection), connection, runId);
            return true;
        }

        String exitReason = aggregateExitReasonOf(p,
                closed.stream().map(ExecutorPositionLeg::exitReason).toList());
        bookClose(p, new ResolvedExit(exitReason, weightedLegExitPrice(closed), "FILL", p, null),
                connection, runId);
        return true;
    }

    /** Quantity-weighted exit price over already-CLOSED legs. Only reached once every leg has been
     *  checked to carry a positive price and a positive qty, so the denominator cannot be zero. */
    private BigDecimal weightedLegExitPrice(List<ExecutorPositionLeg> closed) {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal totalQty = BigDecimal.ZERO;
        for (ExecutorPositionLeg leg : closed) {
            weighted = weighted.add(leg.exitPrice().multiply(leg.qty()));
            totalQty = totalQty.add(leg.qty());
        }
        return weighted.divide(totalQty, 6, RoundingMode.HALF_UP);
    }

    /**
     * FILLED orders that ARE one of this position's own recorded stop legs
     * ({@code stop_order_id} / {@code tranche2_stop_order_id}) and that no OPEN leg claimed as its
     * exit.
     *
     * <p>This is the hole the leg rewrite opened. A tranche whose entry AND whose stop both fill
     * between two nightly passes never becomes a leg at all — {@link #seedLegsFromWorkingStops}
     * only ever sees a WORKING stop, and by the next pass that stop is filled, not working — while
     * {@link #matchLegFills} iterates the legs that DO exist and so can never look at the fill.
     * The surviving legs then agree with the broker's holding and the pass ends at
     * {@link #updateMaintenance}: no TRIM, no realized R, no escalation, for shares that
     * demonstrably left. Before the legs existed the same state raised {@code TRANCHE2_DESYNC};
     * silence is a regression, not a simplification.
     *
     * <p>Matched on the position's OWN stop-order ids only, never on {@code parentId} or role: a
     * stop id is our own record of an order we placed for this position, and it is the one match
     * that cannot be a coincidence. A leg that exits normally nulls its stop-leg column
     * ({@code clearStopLeg}) or closes with the whole row, so a fill matched here is by
     * construction one the book has not accounted for.
     */
    private List<BrokerOrder> unclaimedStopFills(ExecutorPosition p, List<BrokerOrder> filledOrders,
            List<LegFill> fills) {
        Set<String> claimed = fills.stream().map(f -> f.order().orderId())
                .filter(id -> id != null).collect(Collectors.toSet());
        return filledOrders.stream()
                .filter(o -> o.status() == OrderStatus.FILLED)
                .filter(o -> o.orderId() != null && !claimed.contains(o.orderId()))
                .filter(o -> matchesKnownStopLeg(p, o))
                .toList();
    }

    /** Escalates {@link #unclaimedStopFills}. The order ids and the legs the book does hold go
     *  into {@code inputs_snapshot} so an operator can see immediately WHICH stop filled and what
     *  the book still thinks it holds, rather than reading it out of the prose. */
    private void escalateUnclaimedStopFill(ExecutorPosition p, List<ExecutorPositionLeg> legs,
            List<BrokerOrder> unclaimed, BrokerPosition bp, String runId) {
        String orderIds = unclaimed.stream().map(BrokerOrder::orderId)
                .reduce((a, b) -> a + "," + b).orElse("");
        BigDecimal legsQty = legs.stream().map(ExecutorPositionLeg::qty)
                .filter(q -> q != null).reduce(BigDecimal.ZERO, BigDecimal::add);

        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("filled_stop_order_ids", orderIds);
        inputs.put("open_leg_count", legs.size());
        inputs.put("book_qty", p.qty());
        inputs.put("legs_qty", legsQty);
        if (bp != null) {
            inputs.put("broker_qty", bp.qty());
        }
        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("position_id", p.id());

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                "ESCALATE", "UNCLAIMED_STOP_FILL", orderJson,
                "position " + p.symbol() + " (id " + p.id() + "): stop order(s) " + orderIds
                        + " filled at the broker, but no open leg of this position claims them — "
                        + "the shares behind that fill left the position without a TRIM or a "
                        + "realized R. The row was left untouched — UNCLAIMED_STOP_FILL — "
                        + "operator attention required", null, null, null));
    }

    /**
     * Records that a finding which would otherwise close or resize the book on "the broker no
     * longer accounts for these shares" evidence was withheld this pass because the fill-order
     * history call failed. Missing evidence is not the same as evidence of absence: without it, a
     * vanished/shrunk position cannot be told apart from one that genuinely exited at a price we
     * never saw, and booking that guess would invent a realized R. The row is left completely
     * untouched (not even a qty sync) and an operator has to look.
     *
     * <p>{@code withheld} is the discriminator between the two findings this can preempt --
     * {@code RECONCILE_GONE} (would have closed) or {@code QTY_SYNC_SHORTFALL} (would have synced
     * a quantity down) -- carried in {@code inputs_snapshot} rather than left to the {@code
     * reasoning} prose, alongside the same book/legs/broker quantities {@link
     * #escalateLegQtyDesync} already surfaces for its sibling alarm. One reason code
     * ({@code FILL_HISTORY_UNAVAILABLE}) covers both: the root cause is singular (the evidence
     * channel failed), only the withheld action differs, unlike {@code LEG_QTY_DESYNC} vs.
     * {@code TRANCHE2_DESYNC} where two genuinely different conditions used to share one name.
     * {@code legsQty}/{@code brokerQty} are omitted from the snapshot (not written as null) when
     * not applicable to the withheld finding -- the legless chain has no legs, and a vanished
     * position has no broker quantity to report.
     */
    private void escalateMissingEvidence(ExecutorPosition p, String runId, String detail,
            String withheld, BigDecimal bookQty, BigDecimal legsQty, BigDecimal brokerQty) {
        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("withheld", withheld);
        inputs.put("book_qty", bookQty);
        if (legsQty != null) {
            inputs.put("legs_qty", legsQty);
        }
        if (brokerQty != null) {
            inputs.put("broker_qty", brokerQty);
        }

        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("position_id", p.id());

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                "ESCALATE", "FILL_HISTORY_UNAVAILABLE", orderJson,
                "position " + p.symbol() + " (id " + p.id() + "): " + detail
                        + " — the fill-order history was unavailable this pass, so it was left "
                        + "OPEN and unbooked rather than closed or resized on a guess — "
                        + "FILL_HISTORY_UNAVAILABLE — operator attention required", null, null, null));
    }

    /**
     * True when the broker holds LESS than the book and there is exactly one open leg to charge
     * the difference to.
     *
     * <p>Without this, a position that has legs could never sync its quantity down again: any
     * mismatch would hit {@link #escalateLegQtyDesync}, which precedes {@link #updateMaintenance},
     * and the prod-verified QTY_SYNC path (2026-08-06: the book claimed twice the shares the
     * broker actually held, and every quantity-based veto computed on the phantom half)
     * would be unreachable. With exactly one
     * open leg the leg IS the position, so the shortfall is unambiguously that leg's — a
     * tranche-2 limit that never filled, or a partially filled entry. Nothing is guessed: the
     * broker's own number is written.
     *
     * <p>Deliberately one-directional. MORE shares than the book knows about cannot be charged to
     * a leg — that is capital from somewhere the book has no record of, and it escalates.
     */
    private boolean brokerShortfallAttributableToOneLeg(List<ExecutorPositionLeg> legs, BrokerPosition bp) {
        if (legs.size() != 1) return false;
        BigDecimal brokerQty = bp.qty();
        BigDecimal legQty = legs.getFirst().qty();
        if (brokerQty == null || brokerQty.signum() <= 0 || legQty == null) return false;
        return brokerQty.compareTo(legQty) < 0;
    }

    /** Converges the one open leg to the broker's holding, then hands the position to the ordinary
     *  maintenance path, which syncs the row's own qty (QTY_SYNC) and ratchets as usual. */
    private ExecutorPosition syncSingleLegDown(ExecutorPosition p, ExecutorPositionLeg leg,
            BrokerPosition bp, String runId) {
        recordLegQtySync(p, leg, bp.qty(), runId);
        return updateMaintenance(p, bp, runId);
    }

    /**
     * Creates the leg rows for tranches the broker has CONFIRMED it holds, and only those.
     *
     * <p><b>A leg carries shares HELD, never shares ORDERED</b> — the same rule
     * {@link ExecutorPosition#qty()} and {@link #syncLegQuantities} already follow. The quantity
     * is therefore read off the tranche's own WORKING protective stop, never off the placement
     * record. The two are not interchangeable: the original V45 backfill derived leg quantities
     * from what the entry orders ASKED for and was wrong for a real two-tranche position, where
     * the broker's protective stops split the holding differently than the orders had requested
     * while summing to the same total — so no sum-based check could have caught it.
     *
     * <p><b>Why an unfilled tranche contributes no leg, structurally.</b> The stop is an IfDone
     * child: before its parent fills it is not a top-level order at all, but lives inside the
     * parent's embedded RelatedOpenOrders (see {@link StopRatchetService}'s note on how Agora
     * resolves a stop leg pre- and post-fill). {@code openOrders} is the top-level OPEN-orders
     * view, so an unfilled tranche's stop cannot appear in it. That is what makes seeding safe
     * for the case that motivated the whole rule — a tranche-2 limit still working and unfilled
     * yields NO leg, rather than an OPEN leg carrying shares the broker does not hold that would
     * park the position in a permanent {@code LEG_QTY_DESYNC} escalation while occupying a slot
     * under the MAX_POSITIONS veto.
     *
     * <p><b>What production actually verified, and what it did not.</b> On 2026-08-25 the working
     * stop quantities of every live position summed exactly to the shares the broker reported as
     * held. Every one of those tranches had filled COMPLETELY, so that check confirms the rule
     * for fully-filled tranches only. It says nothing about a PARTIALLY filled entry — a state
     * that demonstrably occurs ({@link EntryExpiryService} has a dedicated branch for it) — where
     * a stop child activated at its ordered size would report more shares than were ever bought.
     * The held-qty ceiling below exists for exactly that unverified case: the broker's own
     * reported holding caps the sum of the legs, and seeding is refused rather than guessed when
     * the two disagree.
     *
     * <p>Requires the broker to report the position at all ({@code bp != null}): with no holding
     * on the instrument there is nothing any leg could be carrying, whatever an order list says.
     *
     * <p>Re-entrant. Reconcile sees the same working stops on every pass, so the insert is
     * conditional on the tranche being free ({@link ExecutorPositionLegRepository#insertIfAbsent})
     * rather than on a prior read, and a tranche already occupied by a CLOSED or CANCELLED leg is
     * never resurrected.
     */
    private void seedLegsFromWorkingStops(ExecutorPosition p, BrokerPosition bp,
            List<BrokerOrder> openOrders, String runId) {
        // No holding on the instrument -> nothing any leg could be carrying. This is also the
        // ordinary state of an entry that has not filled yet, so it stays SILENT: warning here
        // would fire on every pending entry on every pass.
        if (bp == null) return;

        List<ExecutorPositionLeg> existing = legRepo.findByPosition(p.id());
        Set<Integer> taken = existing.stream()
                .map(ExecutorPositionLeg::tranche)
                .collect(Collectors.toSet());

        List<ExecutorPositionLeg> candidates = new ArrayList<>();
        addSeedCandidate(candidates, p, 1, p.brokerOrderId(), p.stopOrderId(), taken, openOrders);
        addSeedCandidate(candidates, p, 2, p.tranche2OrderId(), p.tranche2StopOrderId(), taken, openOrders);

        if (candidates.isEmpty()) {
            warnNothingSeedable(p, existing);
            return;
        }

        // The held-qty ceiling. Every seeded leg is evidence-backed on its own, but "a WORKING
        // stop's qty is shares HELD" was verified in production only for tranches that filled
        // COMPLETELY (2026-08-25). A partially filled entry is a real state -- EntryExpiryService
        // has a dedicated PARTIALLY_FILLED branch for it -- and if the broker ever activates the
        // IfDone stop child at its ORDERED size while the parent only partly filled, the stop
        // would report more shares than exist. Seeding that number would put the position in the
        // permanent LEG_QTY_DESYNC state this whole project exists to eliminate, and
        // syncLegQuantities would converge to the same wrong figure rather than catch it.
        //
        // The broker's own reported holding is the one number that cannot be inflated that way,
        // so it caps the sum. Legs that ALREADY exist count against the cap too: seeding tranche
        // 2 must not push the total past the holding just because tranche 1 was booked earlier.
        BigDecimal brokerQty = bp.qty();
        if (brokerQty == null || brokerQty.signum() <= 0) {
            // Without a holding figure there is no ceiling to check against, and a leg quantity
            // that cannot be cross-checked is exactly what this guard refuses to write.
            log.warn("cannot seed legs for {} (id {}): broker reports the position but no usable "
                    + "quantity, so the seeded legs could not be checked against the holding",
                    p.symbol(), p.id());
            return;
        }

        BigDecimal openLegQty = existing.stream()
                .filter(ExecutorPositionLeg::isOpen)
                .map(ExecutorPositionLeg::qty)
                .filter(q -> q != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal candidateQty = candidates.stream()
                .map(ExecutorPositionLeg::qty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (openLegQty.add(candidateQty).compareTo(brokerQty) > 0) {
            escalateSeedExceedsHolding(p, candidates, openLegQty, candidateQty, brokerQty, runId);
            return;
        }

        for (ExecutorPositionLeg candidate : candidates) {
            if (legRepo.insertIfAbsent(candidate)) {
                recordLegSeeded(p, candidate, runId);
            }
        }
    }

    /**
     * Adds the leg one tranche would contribute, or nothing at all.
     *
     * <p>The quantity comes from the tranche's own WORKING protective stop and never from the
     * placement record. The two are not interchangeable: the original V45 backfill derived leg
     * quantities from what the entry orders ASKED for and was wrong for a real two-tranche
     * position, where the broker's protective stops split the holding differently than the orders
     * had requested while summing to the same total -- so no sum-based check could have caught it.
     *
     * <p>That a WORKING stop measures filled shares is structural, not merely observed: the stop
     * is an IfDone child, and pre-fill it is not a top-level order at all but lives inside its
     * parent's embedded RelatedOpenOrders (see {@link StopRatchetService}'s note on Agora's leg
     * resolution). {@code openOrders} is the top-level OPEN-orders view, so an unfilled tranche's
     * stop cannot appear in it and that tranche contributes NO leg -- rather than a leg carrying
     * shares the broker does not hold.
     *
     * <p>Matched by order id AND symbol. The id alone is our own record and is normally enough,
     * but a stale or mis-booked id would otherwise import a quantity from an unrelated
     * instrument. The order's ROLE is deliberately not filtered on, for the reason
     * {@link #matchLegFills} already documents: the role is the gateway's guess from the order
     * type, and our own record of what that order is beats the broker's guess.
     */
    private void addSeedCandidate(List<ExecutorPositionLeg> candidates, ExecutorPosition p,
            int tranche, String entryOrderId, String stopOrderId, Set<Integer> taken,
            List<BrokerOrder> openOrders) {
        if (taken.contains(tranche) || stopOrderId == null) return;

        BigDecimal heldQty = openOrders.stream()
                .filter(o -> stopOrderId.equals(o.orderId()))
                .filter(o -> p.symbol().equals(o.symbol()))
                .filter(o -> o.status() == OrderStatus.WORKING)
                .map(BrokerOrder::qty)
                .findFirst().orElse(null);

        // No working stop -> the tranche has not filled (or its stop is gone). Either way there is
        // no confirmed holding to write, and guessing one is the failure this method exists to
        // prevent. CHECK (qty > 0) also makes a non-positive quantity unwritable by construction.
        if (heldQty == null || heldQty.signum() <= 0) return;

        candidates.add(new ExecutorPositionLeg(null, p.id(), tranche, entryOrderId, stopOrderId,
                heldQty, ExecutorPositionLeg.OPEN, null, null, null));
    }

    /**
     * Reports a position the broker holds but which no leg could be seeded for, and which
     * therefore stays on the legacy escalating path indefinitely.
     *
     * <p>Degrading to pre-project behaviour is acceptable; being SILENT about it is not — an
     * unreported version of exactly this condition is why the original defect survived a quarter
     * year. Only positions that end up with NO leg row at all are reported: a position whose
     * tranche 1 is booked and whose tranche-2 limit is merely unfilled is the ordinary case and
     * must not become noise.
     */
    private void warnNothingSeedable(ExecutorPosition p, List<ExecutorPositionLeg> existing) {
        if (!existing.isEmpty()) return;

        if (p.stopOrderId() == null && p.tranche2StopOrderId() == null) {
            log.warn("cannot seed legs for {} (id {}): the broker holds the position but the book "
                    + "records no stop order id, so it stays on the legacy single-row path",
                    p.symbol(), p.id());
        } else {
            // More alarming than a missing id: we know which order should be protecting these
            // shares and the broker is not working it.
            log.warn("cannot seed legs for {} (id {}): the broker holds the position but none of "
                    + "the recorded stop orders ({}, {}) is working — the protective stop may be "
                    + "gone; the position stays on the legacy single-row path",
                    p.symbol(), p.id(), p.stopOrderId(), p.tranche2StopOrderId());
        }
    }

    /** Records that seeding was refused because the candidate legs would have claimed more shares
     *  than the broker reports holding — the partial-fill case the held-qty ceiling guards. */
    private void escalateSeedExceedsHolding(ExecutorPosition p, List<ExecutorPositionLeg> candidates,
            BigDecimal openLegQty, BigDecimal candidateQty, BigDecimal brokerQty, String runId) {
        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("broker_qty", brokerQty);
        inputs.put("open_leg_qty", openLegQty);
        inputs.put("candidate_qty", candidateQty);
        inputs.put("tranches", candidates.stream().map(l -> String.valueOf(l.tranche()))
                .reduce((a, b) -> a + "," + b).orElse(""));
        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("position_id", p.id());

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                "ESCALATE", "LEG_SEED_EXCEEDS_HOLDING", orderJson,
                "refusing to seed legs for " + p.symbol() + " (id " + p.id() + "): the working "
                        + "stops would claim " + openLegQty.add(candidateQty) + " shares against "
                        + brokerQty + " the broker reports holding — a stop working at more than "
                        + "its tranche actually filled. No leg written; operator attention required",
                null, null, null));
    }

    /** The audit row for one seeded leg. */
    private void recordLegSeeded(ExecutorPosition p, ExecutorPositionLeg leg, String runId) {
        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("tranche", leg.tranche());
        inputs.put("qty", leg.qty());
        inputs.put("stop_order_id", leg.stopOrderId());
        inputs.put("entry_order_id", leg.entryOrderId());
        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("position_id", p.id());
        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                "SYNC", "LEG_SEEDED", orderJson, null, null, null, null));
    }

    /**
     * Converges every OPEN leg's {@code qty} to the broker's own number, for the legs whose stop
     * order the broker still reports WORKING at a different size. Returns the legs as they now
     * stand.
     *
     * <p>Same rule the position row already follows in {@link #updateMaintenance}: {@code qty}
     * means shares HELD, never shares intended. Without this the leg quantities would drift
     * exactly the way the position's did (prod 2026-08-06), and every downstream figure computed
     * from them — the trim remainder below, the desync check — would inherit the drift.
     *
     * <p>Only a WORKING stop counts, and its {@code qty} really is shares HELD. The structural
     * reason is that the stop is an IfDone child: pre-fill it is not a top-level order at all but
     * lives inside its parent's embedded RelatedOpenOrders (see {@link StopRatchetService}'s note
     * on Agora's leg resolution), so an order in this OPEN-orders view can only ever measure
     * filled shares, never ordered ones. Confirmed against the live account on 2026-08-25, where
     * every live position's working stop quantities summed exactly to the shares the broker
     * reported holding. The measured figures live in §1.6 of the design spec under {@code docs/}
     * (gitignored) rather than here: a holding is account data and this repository is public.
     *
     * <p>A PARTIALLY_FILLED stop does not count. There part of the tranche has already exited and
     * the order's {@code qty} is its total, not the shares still held; reading it as a holding
     * would book a quantity that is knowably wrong. A non-positive broker quantity is
     * ignored rather than written: {@code executor_position_leg} carries {@code CHECK (qty > 0)},
     * and a leg that really reached zero has to be CLOSED by one of the fill paths, not resized.
     *
     * <p>A no-op entirely when {@code fillHistoryAvailable} is false. A WORKING stop's reported
     * qty can shrink not only because the stop order was modified but because part of the tranche
     * partially filled -- and that is exactly the kind of unobserved fill the fill history exists
     * to confirm. Resizing a leg from it while that channel is broken would (a) silently shrink
     * the leg with no TRIM row and no realized R for the missing shares, and (b) erase the
     * evidence {@link #brokerShortfallAttributableToOneLeg} and {@link #brokerQtyUnaccountedFor}
     * need downstream: once the leg is pre-converged to the broker's smaller number, both checks
     * see no shortfall left to escalate on. Declining the sync here, not just gating the close/
     * resize findings later, is what keeps {@link #escalateMissingEvidence}'s "left completely
     * untouched" claim true on the leg path.
     */
    private List<ExecutorPositionLeg> syncLegQuantities(ExecutorPosition p,
            List<ExecutorPositionLeg> legs, List<BrokerOrder> openOrders, String runId,
            boolean fillHistoryAvailable) {
        if (!fillHistoryAvailable) {
            return legs;
        }
        List<ExecutorPositionLeg> synced = new ArrayList<>(legs.size());
        for (ExecutorPositionLeg leg : legs) {
            BigDecimal brokerQty = leg.stopOrderId() == null ? null : openOrders.stream()
                    .filter(o -> leg.stopOrderId().equals(o.orderId()))
                    .filter(o -> o.status() == OrderStatus.WORKING)
                    .map(BrokerOrder::qty)
                    .findFirst().orElse(null);

            if (brokerQty == null || brokerQty.signum() <= 0 || leg.qty() == null
                    || leg.qty().compareTo(brokerQty) == 0) {
                synced.add(leg);
                continue;
            }

            recordLegQtySync(p, leg, brokerQty, runId);

            synced.add(new ExecutorPositionLeg(leg.id(), leg.positionId(), leg.tranche(),
                    leg.entryOrderId(), leg.stopOrderId(), brokerQty, leg.status(),
                    leg.exitPrice(), leg.exitReason(), leg.closedAt()));
        }
        return synced;
    }

    /** Writes the broker's quantity onto a leg and records it. Never called with a non-positive
     *  quantity — {@code executor_position_leg} carries {@code CHECK (qty > 0)}. */
    private void recordLegQtySync(ExecutorPosition p, ExecutorPositionLeg leg,
            BigDecimal brokerQty, String runId) {
        legRepo.syncLegQty(leg.id(), brokerQty);

        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("leg_id", leg.id());
        inputs.put("tranche", leg.tranche());
        inputs.put("old_qty", leg.qty());
        inputs.put("new_qty", brokerQty);
        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("position_id", p.id());
        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                "SYNC", "LEG_QTY_SYNC", orderJson, null, null, null, null));
    }

    /**
     * The OPEN legs for which a FILLED exit order was observed, at most one order per leg and no
     * order claimed twice.
     *
     * <p>A leg is matched primarily by the identity of its own {@code stop_order_id} — no role
     * filter — for the reason {@link #findFilledExitLeg} already documents: the fill history
     * carries no bracket structure, the gateway guesses the role from the order type, and an
     * order that came back as {@code OTHER} would otherwise be dropped and the fill silently
     * missed. Our own record of what that order is beats the broker's guess. An order matched only
     * by its parent (the leg's entry order) still has to carry a STOP_LOSS/TAKE_PROFIT role, since
     * a parent match alone does not say which leg it is.
     */
    private List<LegFill> matchLegFills(List<ExecutorPositionLeg> legs, List<BrokerOrder> filledOrders) {
        List<LegFill> matched = new ArrayList<>();
        Set<String> claimed = new HashSet<>();
        for (ExecutorPositionLeg leg : legs) {
            for (BrokerOrder o : filledOrders) {
                if (o.status() != OrderStatus.FILLED || claimed.contains(o.orderId())) continue;
                String exitReason = legExitReason(leg, o);
                if (exitReason == null) continue;
                claimed.add(o.orderId());
                matched.add(new LegFill(leg, o, exitReason));
                break;
            }
        }
        return matched;
    }

    /** The exit reason {@code o} stands for on {@code leg}, or null if it is not this leg's exit. */
    private String legExitReason(ExecutorPositionLeg leg, BrokerOrder o) {
        if (leg.stopOrderId() != null && leg.stopOrderId().equals(o.orderId())) {
            // It IS this leg's stop order. Only an explicit TAKE_PROFIT overrides that.
            return o.role() == OrderRole.TAKE_PROFIT ? "TAKE_PROFIT" : "HARD_STOP";
        }
        if (leg.entryOrderId() != null && leg.entryOrderId().equals(o.parentId())) {
            if (o.role() == OrderRole.STOP_LOSS) return "HARD_STOP";
            if (o.role() == OrderRole.TAKE_PROFIT) return "TAKE_PROFIT";
        }
        return null;
    }

    /**
     * Every leg exited: close each leg with its own fill, then book the position row through the
     * unchanged close path so cooldown, notification and {@code outcome_log} stay identical to a
     * single-tranche close. The row's exit price is the quantity-weighted average of the leg
     * fills.
     */
    private void closePositionFromLegs(ExecutorPosition p, List<LegFill> fills,
            BrokerPosition bp, String connection, String runId) {
        Instant closedAt = clock.instant();
        for (LegFill f : fills) {
            legRepo.closeLeg(f.leg().id(), f.price(), f.exitReason(), closedAt);
        }

        String exitReason = aggregateExitReason(p, fills);
        BigDecimal exitPrice = weightedFillPrice(fills);

        if (exitPrice == null) {
            // Every leg filled, but the broker reported no usable price on any of them. Fall back
            // to the same estimate the RECONCILE_GONE path uses and label it MARK, never FILL —
            // a made-up price must not be booked as an observed one.
            log.warn("position {} (id {}): all {} legs filled but no usable fill price reported — "
                    + "booking the close at the mark estimate", p.symbol(), p.id(), fills.size());
            exitPrice = bp != null ? bp.marketPrice() : p.activeStop();
            bookClose(p, new ResolvedExit(exitReason, exitPrice, "MARK", p, null), connection, runId);
            return;
        }

        bookClose(p, new ResolvedExit(exitReason, exitPrice, "FILL", p, null), connection, runId);
    }

    /** The exit reason for a whole-position close assembled from its leg fills. Legs that exited
     *  for different reasons (one stopped out, one took profit) are booked as HARD_STOP: it is the
     *  conservative reading — it applies the cooldown — and the split is logged rather than
     *  averaged away. */
    private String aggregateExitReason(ExecutorPosition p, List<LegFill> fills) {
        return aggregateExitReasonOf(p, fills.stream().map(LegFill::exitReason).toList());
    }

    /** Same rule over bare reason strings, for a close assembled from leg ROWS rather than from
     *  fresh fills ({@link #completeInterruptedClose}). */
    private String aggregateExitReasonOf(ExecutorPosition p, List<String> reasons) {
        boolean anyStop = reasons.stream().anyMatch("HARD_STOP"::equals);
        boolean anyTarget = reasons.stream().anyMatch("TAKE_PROFIT"::equals);
        if (anyStop && anyTarget) {
            log.warn("position {} (id {}): legs exited for different reasons {} — booking the row "
                    + "as HARD_STOP (the conservative reading); per-leg reasons stay on the legs",
                    p.symbol(), p.id(), reasons);
        }
        return anyStop ? "HARD_STOP" : "TAKE_PROFIT";
    }

    /** Quantity-weighted average price over the fills that reported a usable one, or null if none
     *  did. Legs without a price are left out of both sides of the average rather than counted at
     *  zero, which would drag the result toward a price nothing traded at. */
    private BigDecimal weightedFillPrice(List<LegFill> fills) {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal totalQty = BigDecimal.ZERO;
        for (LegFill f : fills) {
            if (f.price() == null || f.price().signum() <= 0 || f.qty() == null || f.qty().signum() <= 0) {
                continue;
            }
            weighted = weighted.add(f.price().multiply(f.qty()));
            totalQty = totalQty.add(f.qty());
        }
        if (totalQty.signum() <= 0) return null;
        return weighted.divide(totalQty, 6, RoundingMode.HALF_UP);
    }

    /**
     * Some but not all legs exited: close the filled legs and shrink the row to the surviving
     * legs' quantity, leaving it OPEN. Returns the survivor as it now stands, so the rest of the
     * pipeline (hard triggers, exposure/heat, LLM context) sees the shares actually held.
     *
     * <p>The TRIM decision is written in the shape of the webhook partial-exit path
     * ({@code ExecutorWebhookController}); {@code OutcomeBatchJob.weightedRealizedR} reads
     * {@code qty_closed}/{@code price}/{@code position_id} out of it, and a differently shaped row
     * would silently drop this leg out of the weighted realized R.
     */
    private ExecutorPosition trimToSurvivingLegs(ExecutorPosition p, List<ExecutorPositionLeg> legs,
            List<LegFill> fills, String runId) {
        Instant closedAt = clock.instant();
        for (LegFill f : fills) {
            legRepo.closeLeg(f.leg().id(), f.price(), f.exitReason(), closedAt);
            // The stop that just filled is gone at the broker, but the 3-arg recordTrim below
            // does not touch the stop columns, so the column naming it would stay behind as a
            // dead id. That still matters for a position with NO leg rows: StopRatchetService's
            // legacy column path addresses that leg by name, gets LEG_NOT_FOUND, and the position
            // spends a maintenance cycle in an escalation we caused ourselves. A legged position
            // is no longer exposed to it — the ratchet reads the OPEN legs and this one is closed
            // above — but the columns are still read elsewhere, so they are kept honest here.
            positionRepo.clearStopLeg(p.id(), f.leg().stopOrderId());
        }

        Set<Long> closedLegIds = fills.stream().map(f -> f.leg().id()).collect(java.util.stream.Collectors.toSet());
        BigDecimal qtyClosed = fills.stream().map(LegFill::qty)
                .filter(q -> q != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal qtyRemaining = legs.stream()
                .filter(l -> !closedLegIds.contains(l.id()))
                .map(ExecutorPositionLeg::qty)
                .filter(q -> q != null).reduce(BigDecimal.ZERO, BigDecimal::add);

        int newTrimCount = p.trimCount() + 1;
        positionRepo.recordTrim(p.id(), qtyRemaining, newTrimCount);

        BigDecimal price = weightedFillPrice(fills);
        if (price == null) {
            // The trim still happens. The shares are gone whether or not the broker told us what
            // they went for, and leaving the book claiming them is exactly the phantom-position
            // bug this change exists to kill — the quantity is known, only the price is not.
            //
            // But the price stays null rather than falling back to a mark estimate the way
            // closePositionFromLegs does. That path MUST produce an exit price (the row is being
            // closed and needs one), so it labels its estimate MARK. Here a substituted price
            // would be silently multiplied by qty_closed into a fabricated realized R for this
            // leg. OutcomeBatchJob is built for exactly this: a missing price sets computable =
            // false and it falls back to the position's own realized_r rather than inventing a
            // weighted figure. Dropping the leg from the weighted R beats faking it — but it must
            // never happen quietly, hence the warning and the explicit inputs flag.
            log.warn("position {} (id {}): {} leg(s) filled with no usable fill price reported — "
                    + "trimming on the known quantity ({} shares), booking the TRIM without a "
                    + "price; its weighted realized R will fall back to the final leg",
                    p.symbol(), p.id(), fills.size(), qtyClosed);
        }

        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("qty_closed", qtyClosed);
        orderJson.put("qty_remaining", qtyRemaining);
        if (p.qty() != null && p.qty().signum() > 0) {
            orderJson.put("fraction", qtyClosed.divide(p.qty(), 4, RoundingMode.HALF_UP));
        }
        orderJson.put("price", price);
        // Exact position linkage for the outcome batch job (decision_log has no position_id
        // column; order_json carries it).
        orderJson.put("position_id", p.id());

        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("book_qty", p.qty());
        inputs.put("legs_closed", fills.size());
        inputs.put("legs_remaining", legs.size() - fills.size());
        inputs.put("fill_price_available", price != null);

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                "TRIM", "RECONCILE_TRIM", orderJson,
                "position " + p.symbol() + " (id " + p.id() + "): " + fills.size() + " of "
                        + legs.size() + " legs exited at the broker — trimmed to " + qtyRemaining
                        + " shares, row stays OPEN", null, null, null));

        String closedText = price == null
                ? qtyClosed + " shares, no fill price reported by the broker"
                : qtyClosed + " shares at " + price;
        telegram.notifyAlert(p.symbol(), "POSITION_TRIMMED", "INFO",
                "one of " + p.symbol() + "'s broker tranches exited on its own (" + closedText
                        + ") — book trimmed to " + qtyRemaining
                        + " shares, position stays open");

        Set<String> deadStopIds = fills.stream().map(f -> f.leg().stopOrderId())
                .filter(id -> id != null).collect(java.util.stream.Collectors.toSet());
        String stopOrderId = deadStopIds.contains(p.stopOrderId()) ? null : p.stopOrderId();
        String tranche2StopOrderId = deadStopIds.contains(p.tranche2StopOrderId())
                ? null : p.tranche2StopOrderId();

        // Nulling the dead id is only half the bookkeeping. A two-tranche row that now names one
        // stop leg and does not say why is precisely the state ratchetTwoLegs escalates as
        // TRANCHE_RATCHET_UNSUPPORTED — on every maintenance run, on a state this trim created,
        // and immediately, because MaintenancePipeline feeds these survivors into the same pass.
        // Recording the collapse routes the survivor down the single-leg ratchet instead, which is
        // what it now is. Only when fewer than two legs are actually named: the flag explains a
        // missing id, it must never be used to decide how many legs there are (BUG-S13).
        boolean expectsTwoLegs = p.tranche() >= 2 || p.tranche2OrderId() != null
                || p.tranche2StopOrderId() != null;
        boolean bothLegsNamed = stopOrderId != null && tranche2StopOrderId != null;
        boolean collapsed = p.stopLegsCollapsed() || (expectsTwoLegs && !bothLegsNamed);
        if (collapsed && !p.stopLegsCollapsed()) {
            positionRepo.markStopLegsCollapsed(p.id());
        }

        return withTrim(p, qtyRemaining, newTrimCount, stopOrderId, tranche2StopOrderId, collapsed);
    }

    /** True when the broker holds a quantity the OPEN legs do not account for. A missing or
     *  non-positive broker quantity is not a disagreement — the same fail-soft rule
     *  {@link #updateMaintenance} applies before it will sync a quantity. */
    private boolean brokerQtyUnaccountedFor(List<ExecutorPositionLeg> legs, BrokerPosition bp) {
        BigDecimal brokerQty = bp.qty();
        if (brokerQty == null || brokerQty.signum() <= 0) return false;
        BigDecimal legsQty = legs.stream().map(ExecutorPositionLeg::qty)
                .filter(q -> q != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        return legsQty.compareTo(brokerQty) != 0;
    }

    /**
     * The broker still holds the symbol, but at a quantity the OPEN legs do not add up to and no
     * observed fill explains. Nothing may be closed on that: it records the escalation with book,
     * leg and broker quantities side by side and leaves the row OPEN for an operator. Capital
     * protection meanwhile comes from the broker-held stops, not from this book row.
     *
     * <p>Deliberately NOT the legacy {@code TRANCHE2_DESYNC} code, which the legless path still
     * emits: that one fires when a two-bracket row saw an exit leg fill it cannot attribute, this
     * one when a broker holding cannot be reconciled to the legs. Two different conditions sharing
     * one alarm name is what makes an alarm unreadable. Nothing outside the Java code keys on
     * either name — the daily analysis alarms generically on {@code action='ESCALATE'}.
     */
    private void escalateLegQtyDesync(ExecutorPosition p, List<ExecutorPositionLeg> legs,
            BrokerPosition bp, String runId) {
        BigDecimal legsQty = legs.stream().map(ExecutorPositionLeg::qty)
                .filter(q -> q != null).reduce(BigDecimal.ZERO, BigDecimal::add);

        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("book_qty", p.qty());
        inputs.put("legs_qty", legsQty);
        inputs.put("broker_qty", bp.qty());
        inputs.put("open_legs", legs.size());

        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("position_id", p.id());

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                "ESCALATE", "LEG_QTY_DESYNC", orderJson,
                "position " + p.symbol() + " (id " + p.id() + "): broker holds " + bp.qty()
                        + " but the " + legs.size() + " open leg(s) account for " + legsQty
                        + " and no observed fill explains the difference — LEG_QTY_DESYNC — "
                        + "operator attention required", null, null, null));
    }

    /** Copy of {@code p} after a trim, carrying everything this pass wrote to the row: the
     *  surviving quantity, the bumped trim count, the soft-confirm streak reset that
     *  {@link ExecutorPositionRepository#recordTrim} persists, the stop-leg columns of the filled
     *  tranches cleared, and the collapse flag that explains a cleared one. The rest of THIS pass
     *  reads this record, so anything missing here is a stale value the maintenance pipeline acts
     *  on before the next reconcile run corrects it. */
    private static ExecutorPosition withTrim(ExecutorPosition p, BigDecimal qty, int trimCount,
            String stopOrderId, String tranche2StopOrderId, boolean collapsed) {
        return new ExecutorPosition(p.id(), p.connection(), p.symbol(), p.side(), qty,
                p.entryPrice(), p.initialStop(), p.activeStop(), p.tranche(), p.rValue(),
                p.killCriteria(), p.sourceSignalId(), p.sourceAgent(), p.entryDate(), p.mfe(),
                p.status(), p.brokerOrderId(), p.highestPrice(), p.mfeR(), 0,
                p.exitPrice(), p.realizedR(), p.exitReason(), p.closedAt(), stopOrderId,
                p.sector(), p.entryDayHigh(), p.tranche2OrderId(), tranche2StopOrderId,
                trimCount, p.lowestPrice(), p.entryExpiresAt(), p.submittedLimitPrice(),
                p.pendingExitReason(), p.exitOrderId(), p.pendingExitFillPrice(), collapsed);
    }

    /**
     * Finalizes a pending-exit row (hard-trigger flatten or fill-less webhook FULL exit already
     * submitted, see {@code pending_exit_reason}/{@code exit_order_id}) once the broker confirms
     * it is really gone, or leaves it OPEN+pending untouched otherwise. This is the fix for the
     * verified PSMT incident: closing here before the broker confirms can book a wrong exit
     * price/R while the broker still holds shares and a working exit order.
     *
     * <p>Finalization gate: the broker no longer reports the position ({@code bp == null}) AND
     * {@code exit_order_id} is not reported WORKING/PARTIALLY_FILLED. Exit price precedence:
     * the matched filled exit leg's {@code avgFillPrice} (source FILL) → the fill price stamped
     * at submit time, {@code pending_exit_fill_price} (source FILL) → the position's
     * {@code active_stop} as a last resort (source MARK, no fill data available at all).
     *
     * <p>{@code openOrders} answers "is the exit still working?" and {@code filledOrders} answers
     * "what did it fill at?" — two different broker views. Reading the fill out of the open-orders
     * list is what used to make the FILL branch unreachable, silently demoting every finalization
     * to the submit-time price or the MARK estimate.
     */
    private void finalizePendingExitOrKeep(ExecutorPosition p, BrokerPosition bp,
            List<BrokerOrder> openOrders, List<BrokerOrder> filledOrders, String runId,
            List<ExecutorPosition> survivors) {
        boolean exitOrderStillWorking = p.exitOrderId() != null && openOrders.stream()
                .filter(o -> p.exitOrderId().equals(o.orderId()))
                .anyMatch(o -> o.status() == OrderStatus.WORKING || o.status() == OrderStatus.PARTIALLY_FILLED);

        if (bp != null || exitOrderStillWorking) {
            // Not confirmed gone yet -> leave the row exactly as-is. No re-evaluation of hard
            // triggers/ratchets happens here (this branch is taken instead of all other reconcile
            // logic), so this can never double-flatten an already-submitted exit.
            escalateIfPendingExitStale(p, runId);
            survivors.add(p);
            return;
        }

        BrokerOrder filledExitLeg = p.exitOrderId() == null ? null : filledOrders.stream()
                .filter(o -> o.status() == OrderStatus.FILLED)
                .filter(o -> p.exitOrderId().equals(o.orderId()))
                .findFirst().orElse(null);

        BigDecimal exitPrice;
        String exitPriceSource;
        if (filledExitLeg != null && filledExitLeg.avgFillPrice() != null) {
            exitPrice = filledExitLeg.avgFillPrice();
            exitPriceSource = "FILL";
        } else if (p.pendingExitFillPrice() != null) {
            exitPrice = p.pendingExitFillPrice();
            exitPriceSource = "FILL";
        } else {
            exitPrice = p.activeStop();
            exitPriceSource = "MARK";
        }

        RCalc rCalc = computeR(p, exitPrice);
        BigDecimal realizedR = rCalc.r();
        String exitReason = p.pendingExitReason();

        positionRepo.close(p.id(), exitPrice, realizedR, exitReason, exitPriceSource, rCalc.denominator());
        // Book every leg out with the row. A flatten exits the WHOLE position, so no leg can
        // survive it, and an OPEN leg on a CLOSED position would falsify the one invariant the
        // leg table exists to hold. Nothing reads those legs today (findOpenByPosition is only
        // reached for open positions), but seeding turns leftovers from a backfill artefact into
        // the normal outcome of every hard-trigger flatten and webhook FULL exit.
        Instant legClosedAt = clock.instant();
        for (ExecutorPositionLeg leg : legRepo.findOpenByPosition(p.id())) {
            legRepo.closeLeg(leg.id(), exitPrice, exitReason, legClosedAt);
        }
        cooldownRepo.add(p.symbol(), exitReason,
                clock.instant().plus(Duration.ofDays(cooldownDays)), "fresh setup only");

        String action = HARD_REASONS.contains(exitReason) ? "LOG_HARD_EXIT" : "RECONCILE_CLOSE";

        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("exit_price", exitPrice);
        inputs.put("realized_r", realizedR);
        inputs.put("entry_price", p.entryPrice());
        inputs.put("initial_stop", p.initialStop());
        inputs.put("exit_price_source", exitPriceSource);

        // Exact position linkage for the outcome batch job (decision_log has no position_id
        // column; order_json carries it). A pending-exit finalization is always a full flatten.
        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("fraction", 1.0);
        orderJson.put("position_id", p.id());

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                action, exitReason, orderJson,
                "pending exit confirmed for " + p.symbol() + ": broker no longer holds the position "
                        + "and the exit order is no longer working — booking the close",
                null, null, null));

        executorNotifier.notifyExit(p, exitReason, exitPrice, realizedR, p.connection());
    }

    /**
     * Spec §4.3 (a4-netpositions-first-design): a pending exit that never confirms escalates via
     * the existing CRITICAL path (decision log + Telegram) — no auto-retry, no auto-close. Gated
     * on {@code exit_submitted_at} age past {@code pendingExitStaleHours}; rate-limited to one
     * alert per pending exit by checking whether a {@code PENDING_EXIT_STALE} row already exists
     * for this symbol created since the CURRENT pending exit's {@code exit_submitted_at} (see
     * {@link DecisionLogRepository#countBySymbolAndReasonCodeSince}) — an escalation from an
     * earlier, already-resolved pending exit on the same symbol must not suppress this one.
     */
    private void escalateIfPendingExitStale(ExecutorPosition p, String runId) {
        Instant submittedAt = positionRepo.exitSubmittedAt(p.id());
        if (submittedAt == null) {
            return;
        }
        Duration age = Duration.between(submittedAt, clock.instant());
        if (age.compareTo(Duration.ofHours(pendingExitStaleHours)) <= 0) {
            return;
        }
        if (decisionRepo.countBySymbolAndReasonCodeSince(p.symbol(), "PENDING_EXIT_STALE", submittedAt) > 0) {
            return;
        }

        long ageHours = age.toHours();
        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("position_id", p.id());

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), null, null,
                "ESCALATE", "PENDING_EXIT_STALE", orderJson,
                "pending exit for " + p.symbol() + " (id " + p.id() + ") has not confirmed after "
                        + ageHours + "h — PENDING_EXIT_STALE — operator attention required, no auto-close",
                null, null, null));
        telegram.notifyAlert(p.symbol(), "PENDING_EXIT_STALE", "CRITICAL",
                "pending exit for " + p.symbol() + " has not confirmed after " + ageHours
                        + "h — check broker order " + p.exitOrderId() + " manually");
    }

    private void closePosition(ExecutorPosition p, BrokerOrder filledLeg, BrokerPosition bp,
            String connection, String runId) {
        bookClose(p, resolveExit(p, filledLeg, bp, connection), connection, runId);
    }

    /**
     * Works out WHAT the exit was — reason, price and the provenance label for that price — for a
     * position the broker no longer holds or whose exit leg was observed filled. Split out from
     * {@link #bookClose} only so the leg-based close ({@link #closePositionFromLegs}) can supply
     * its own aggregate finding and still book it through exactly the same path; the logic below
     * is unchanged.
     */
    private ResolvedExit resolveExit(ExecutorPosition p, BrokerOrder filledLeg, BrokerPosition bp,
            String connection) {
        String exitReason;
        BigDecimal exitPrice;
        String exitPriceSource = null;
        // `effective` carries the real broker entry price once a RECONCILE_GONE match syncs it,
        // so realizedR below is computed from real entry+exit rather than the stale placeholder.
        ExecutorPosition effective = p;
        // Set only for the RECONCILE_GONE matched-fill case: realizedR there must be measured
        // against the ORIGINAL planned risk (planned entry vs initial stop), not recomputed from
        // the synced real entry -- see realizedRAgainstPlannedRisk() for why.
        RCalc rCalcOverride = null;
        if (filledLeg != null && filledLeg.role() == OrderRole.STOP_LOSS) {
            exitReason = "HARD_STOP";
            exitPrice = filledLeg.avgFillPrice();
            exitPriceSource = "FILL";
        } else if (filledLeg != null && filledLeg.role() == OrderRole.TAKE_PROFIT) {
            exitReason = "TAKE_PROFIT";
            exitPrice = filledLeg.avgFillPrice();
            exitPriceSource = "FILL";
        } else {
            // Position vanished from the broker with no filled exit leg observed in this
            // reconcile pass (e.g. it opened AND closed entirely between two reconcile cycles).
            // Look up Agora's real closed-position fills before falling back to the activeStop
            // estimate -- see the ISRG incident (2026-07-17): a gapped-down fill + immediate
            // stop-out between cycles previously booked a fabricated entry/exit pair.
            exitReason = "RECONCILE_GONE";
            exitPrice = null;

            BrokerClosedPosition match = null;
            try {
                List<BrokerClosedPosition> closed = gateway.closedPositions(connection);
                match = closed.stream()
                        .filter(cp -> p.sourceSignalId() != null && p.sourceSignalId().equals(cp.clientRef()))
                        .findFirst()
                        // Symbol-only fallback (no clientRef on p): can bind to the wrong close if the
                        // same symbol closed more than once between reconcile cycles -- BrokerClosedPosition
                        // carries no timestamp to tiebreak. Only triggers when clientRef is absent.
                        .or(() -> closed.stream().filter(cp -> p.symbol().equals(cp.symbol())).findFirst())
                        .orElse(null);
            } catch (Exception e) {
                // Any gateway failure here must never abort reconcile -- fall through to the
                // labeled activeStop estimate below, same as if no match had been found.
                match = null;
            }

            // Guard against a malformed upstream fill (e.g. a Saxo field-mapping bug returning
            // 0.00/null prices): booking entry=0/exit=0 would corrupt realizedR far worse than
            // the labeled placeholder, so treat a non-positive/null price as "no usable match".
            if (match != null && !hasUsablePrices(match)) {
                match = null;
            }

            if (match != null) {
                positionRepo.syncEntryPrice(p.id(), match.openPrice());
                effective = new ExecutorPosition(p.id(), p.connection(), p.symbol(), p.side(), p.qty(),
                        match.openPrice(), p.initialStop(), p.activeStop(), p.tranche(), p.rValue(),
                        p.killCriteria(), p.sourceSignalId(), p.sourceAgent(), p.entryDate(), p.mfe(),
                        p.status(), p.brokerOrderId(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                        p.exitPrice(), p.realizedR(), p.exitReason(), p.closedAt(), p.stopOrderId(),
                        p.sector(), p.entryDayHigh(), p.tranche2OrderId(), p.tranche2StopOrderId(),
                        p.trimCount(), p.lowestPrice(), p.entryExpiresAt(), p.submittedLimitPrice(),
                        p.pendingExitReason(), p.exitOrderId(), p.pendingExitFillPrice(), p.stopLegsCollapsed());
                exitPrice = match.closePrice();
                exitPriceSource = "FILL";
                rCalcOverride = realizedRAgainstPlannedRisk(p, match.openPrice(), match.closePrice());
            } else {
                exitPriceSource = "RECONCILE_GONE";
            }
        }
        if (exitPrice == null) {
            exitPrice = bp != null ? bp.marketPrice() : p.activeStop();
        }
        return new ResolvedExit(exitReason, exitPrice, exitPriceSource, effective, rCalcOverride);
    }

    /** What a close is going to be booked as: the exit reason, the exit price and the label for
     *  where that price came from, plus the position record the R math must use ({@code effective}
     *  carries a synced real entry price) and an optional pre-computed R. */
    private record ResolvedExit(String exitReason, BigDecimal exitPrice, String exitPriceSource,
            ExecutorPosition effective, RCalc rCalcOverride) {
    }

    /** Books a resolved close: position row, cooldown, decision log and exit notification. Every
     *  close in this class goes through here, so the four leg findings and the single-row path
     *  produce byte-identical bookkeeping. */
    private void bookClose(ExecutorPosition p, ResolvedExit resolved, String connection, String runId) {
        String exitReason = resolved.exitReason();
        BigDecimal exitPrice = resolved.exitPrice();
        String exitPriceSource = resolved.exitPriceSource();
        ExecutorPosition effective = resolved.effective();
        RCalc rCalcOverride = resolved.rCalcOverride();

        // rCalcOverride is non-null whenever the RECONCILE_GONE matched-fill branch ran, even
        // when the planned-risk guard rejected the denominator (plannedRisk <= 0) and its .r()
        // is null -- that case must fall through to computeR exactly like base did when
        // realizedRAgainstPlannedRisk itself returned null, not book a null realized_r.
        RCalc rCalc = (rCalcOverride != null && rCalcOverride.r() != null)
                ? rCalcOverride : computeR(effective, exitPrice);
        BigDecimal realizedR = rCalc.r();

        if (exitPriceSource != null) {
            positionRepo.close(p.id(), exitPrice, realizedR, exitReason, exitPriceSource, rCalc.denominator());
        } else {
            positionRepo.close(p.id(), exitPrice, realizedR, exitReason, rCalc.denominator());
        }
        cooldownRepo.add(p.symbol(), exitReason,
                clock.instant().plus(Duration.ofDays(cooldownDays)), "fresh setup only");

        String action = ("HARD_STOP".equals(exitReason) || "TAKE_PROFIT".equals(exitReason))
                ? "LOG_HARD_EXIT" : "RECONCILE_CLOSE";

        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("exit_price", exitPrice);
        inputs.put("realized_r", realizedR);
        inputs.put("entry_price", effective.entryPrice());
        inputs.put("initial_stop", effective.initialStop());
        if (exitPriceSource != null) {
            inputs.put("exit_price_source", exitPriceSource);
        }

        // Exact position linkage for the outcome batch job (decision_log has no position_id
        // column; order_json carries it). A reconcile close is always a full flatten.
        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("fraction", 1.0);
        orderJson.put("position_id", p.id());

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                action, exitReason, orderJson, null, null, null, null));

        executorNotifier.notifyExit(effective, exitReason, exitPrice, realizedR, connection);
    }

    /** {@code true} only if both prices are present and strictly positive -- guards against a
     *  malformed upstream fill (0.00/null) being booked as a real fill. */
    private boolean hasUsablePrices(BrokerClosedPosition match) {
        return match.openPrice() != null && match.openPrice().signum() > 0
                && match.closePrice() != null && match.closePrice().signum() > 0;
    }

    /** Copy of {@code p} with only {@code qty} replaced — the 38-component record has no wither. */
    private static ExecutorPosition withQty(ExecutorPosition p, BigDecimal qty) {
        return new ExecutorPosition(p.id(), p.connection(), p.symbol(), p.side(), qty,
                p.entryPrice(), p.initialStop(), p.activeStop(), p.tranche(), p.rValue(),
                p.killCriteria(), p.sourceSignalId(), p.sourceAgent(), p.entryDate(), p.mfe(),
                p.status(), p.brokerOrderId(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                p.exitPrice(), p.realizedR(), p.exitReason(), p.closedAt(), p.stopOrderId(),
                p.sector(), p.entryDayHigh(), p.tranche2OrderId(), p.tranche2StopOrderId(),
                p.trimCount(), p.lowestPrice(), p.entryExpiresAt(), p.submittedLimitPrice(),
                p.pendingExitReason(), p.exitOrderId(), p.pendingExitFillPrice(), p.stopLegsCollapsed());
    }

    private ExecutorPosition updateMaintenance(ExecutorPosition p, BrokerPosition bp, String runId) {
        // The broker actually holds this position -> the entry is confirmed filled. Clear the
        // GTD expiry marker: from here on `entry_expires_at IS NULL` doubles as the persisted
        // "entry filled" flag (set at placement, cleared here on fill or by EntryExpiryService
        // on cancel), which ExecutorWebhookController.exitPosition uses to gate LLM exits.
        boolean entryJustFilled = p.entryExpiresAt() != null;
        if (entryJustFilled) {
            positionRepo.clearEntryExpiry(p.id());
        }

        // Book = broker: the broker's average open price is the entry-price truth. The submitted
        // limit stays in submitted_limit_price (slippage = entry_price - submitted_limit_price).
        // Idempotent: converges after tranche-2 fills too; logs only on an actual change.
        BigDecimal brokerBasis = bp.avgEntryPrice();
        if (brokerBasis != null && brokerBasis.signum() > 0
                && p.entryPrice().compareTo(brokerBasis) != 0) {
            positionRepo.syncEntryPrice(p.id(), brokerBasis);
            ObjectNode inputs = mapper.createObjectNode();
            inputs.put("old_entry_price", p.entryPrice());
            inputs.put("new_entry_price", brokerBasis);
            ObjectNode orderJson = mapper.createObjectNode();
            orderJson.put("position_id", p.id());
            decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                    "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                    "SYNC", "ENTRY_PRICE_SYNC", orderJson, null, null, null, null));
            p = new ExecutorPosition(p.id(), p.connection(), p.symbol(), p.side(), p.qty(),
                    brokerBasis, p.initialStop(), p.activeStop(), p.tranche(), p.rValue(),
                    p.killCriteria(), p.sourceSignalId(), p.sourceAgent(), p.entryDate(), p.mfe(),
                    p.status(), p.brokerOrderId(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                    p.exitPrice(), p.realizedR(), p.exitReason(), p.closedAt(), p.stopOrderId(),
                    p.sector(), p.entryDayHigh(), p.tranche2OrderId(), p.tranche2StopOrderId(),
                    p.trimCount(), p.lowestPrice(), p.entryExpiresAt(), p.submittedLimitPrice(),
                    p.pendingExitReason(), p.exitOrderId(), p.pendingExitFillPrice(), p.stopLegsCollapsed());
        }

        // Book = broker for QUANTITY too. `qty` means shares HELD (see ExecutorPosition), so the
        // broker's reported holding is the truth and the book follows it. This is what closes the
        // window in which a submitted-but-unfilled tranche-2 limit (or a partially filled entry)
        // left the book claiming more shares than exist: on 2026-08-06 two live positions were
        // each booked at twice the shares the broker actually held, and every
        // quantity-based action — the exit_position flatten remainder, the exposure/heat veto
        // inputs — computed on the phantom half. Idempotent, logs only on an actual change; a
        // null/non-positive broker qty is ignored rather than blanking a good book value.
        BigDecimal brokerQty = bp.qty();
        if (brokerQty != null && brokerQty.signum() > 0
                && p.qty() != null && p.qty().compareTo(brokerQty) != 0) {
            positionRepo.syncQty(p.id(), brokerQty);
            ObjectNode inputs = mapper.createObjectNode();
            inputs.put("old_qty", p.qty());
            inputs.put("new_qty", brokerQty);
            ObjectNode orderJson = mapper.createObjectNode();
            orderJson.put("position_id", p.id());
            decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                    "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                    "SYNC", "QTY_SYNC", orderJson, null, null, null, null));
            p = withQty(p, brokerQty);
        }

        if (entryJustFilled) {
            executorNotifier.notifyEntryFilled(p, p.qty(),
                    brokerBasis != null ? brokerBasis : p.entryPrice(), p.connection());
        }

        BigDecimal currentClose = bp.marketPrice();
        BigDecimal baseHighest = p.highestPrice() == null ? p.entryPrice() : p.highestPrice();
        // highest_price is the favorable price extreme: highest for a long, lowest for a short.
        BigDecimal newHighest = "SELL".equalsIgnoreCase(p.side())
                ? baseHighest.min(currentClose)
                : baseHighest.max(currentClose);

        BigDecimal currentR = computeR(p, currentClose).r();
        BigDecimal baseMfe = p.mfeR() == null ? BigDecimal.ZERO : p.mfeR();
        BigDecimal newMfeR = currentR == null ? baseMfe : baseMfe.max(currentR);

        positionRepo.updateMaintenance(p.id(), newHighest, newMfeR, p.softConfirmCount(),
                p.activeStop(), null);

        return new ExecutorPosition(p.id(), p.connection(), p.symbol(), p.side(), p.qty(),
                p.entryPrice(), p.initialStop(), p.activeStop(), p.tranche(), p.rValue(),
                p.killCriteria(), p.sourceSignalId(), p.sourceAgent(), p.entryDate(), p.mfe(),
                p.status(), p.brokerOrderId(), newHighest, newMfeR, p.softConfirmCount(),
                p.exitPrice(), p.realizedR(), p.exitReason(), p.closedAt(), p.stopOrderId(),
                p.sector(), p.entryDayHigh(), p.tranche2OrderId(), p.tranche2StopOrderId(),
                p.trimCount(), p.lowestPrice(), null, p.submittedLimitPrice(),
                p.pendingExitReason(), p.exitOrderId(), p.pendingExitFillPrice(), p.stopLegsCollapsed());
    }

    /** Realized R together with the denominator (risk-per-share) it was actually divided by, so
     *  the same expression that produces {@code realized_r} also produces what gets persisted
     *  into {@code r_value} — see {@link ExecutorPositionRepository#close}. {@code r} is null
     *  exactly when the denominator was unusable (zero or non-positive risk); {@code denominator}
     *  is then also null so nothing meaningless gets persisted in that case. */
    private record RCalc(BigDecimal r, BigDecimal denominator) {
    }

    /** Realized R for a matched RECONCILE_GONE close, measured against the ORIGINAL planned risk-per-share
     *  (planned entry vs initial stop). We must NOT recompute risk from the synced real fill: a gapped fill can
     *  land on the wrong side of the stop (a long filled below its stop), which inverts an entry-stop denominator
     *  and flips a realized loss into a positive R. Numerator uses the real open/close fills. */
    private RCalc realizedRAgainstPlannedRisk(ExecutorPosition planned, BigDecimal realEntry, BigDecimal realExit) {
        BigDecimal plannedRisk;
        BigDecimal pnl;
        if ("SELL".equals(planned.side())) {
            plannedRisk = planned.initialStop().subtract(planned.entryPrice());
            pnl = realEntry.subtract(realExit);
        } else {
            plannedRisk = planned.entryPrice().subtract(planned.initialStop());
            pnl = realExit.subtract(realEntry);
        }
        if (plannedRisk.signum() <= 0) return new RCalc(null, null);   // guard <= 0, not == 0
        BigDecimal r = pnl.divide(plannedRisk, 6, RoundingMode.HALF_UP);
        return new RCalc(r, plannedRisk);
    }

    private RCalc computeR(ExecutorPosition p, BigDecimal exitPrice) {
        if (exitPrice == null) return new RCalc(null, null);
        BigDecimal denominator;
        BigDecimal numerator;
        if ("SELL".equals(p.side())) {
            numerator = p.entryPrice().subtract(exitPrice);
            denominator = p.initialStop().subtract(p.entryPrice());
        } else {
            numerator = exitPrice.subtract(p.entryPrice());
            denominator = p.entryPrice().subtract(p.initialStop());
        }
        if (denominator.compareTo(BigDecimal.ZERO) == 0) return new RCalc(null, null);
        BigDecimal r = numerator.divide(denominator, 6, RoundingMode.HALF_UP);
        return new RCalc(r, denominator);
    }
}
