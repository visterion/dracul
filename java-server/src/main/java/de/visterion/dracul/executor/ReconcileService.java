package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerClosedPosition;
import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerPosition;
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

/**
 * Reconciles the executor's position book against the broker's actual state: detects
 * stop/target fills (or positions that simply disappeared) and closes them in the book,
 * and otherwise ratchets highest-price/MFE-R bookkeeping for still-open positions.
 *
 * <p>On {@link BrokerUnavailableException} this deliberately does nothing to the book —
 * a transient broker outage must never be mistaken for positions closing.
 *
 * <p><b>Tranche-2 v1 limitation:</b> a position with a second bracket ({@code tranche2OrderId}/
 * {@code tranche2StopOrderId}) has two independent exit legs at the broker, but the book still
 * models it as a single row. When either bracket's exit leg fills (or the whole position vanishes)
 * this class cannot correctly TRIM the row to the surviving tranche's quantity, so it deliberately
 * neither closes nor silently keeps the row: it escalates ({@code TRANCHE2_DESYNC}) and leaves the
 * row OPEN for operator attention. Capital protection in that state comes from the broker-held
 * stops, not from this book row.
 *
 * <p><b>Multi-leg reconciliation is deliberately NOT implemented</b> (BUG-S11). This javadoc used
 * to promise that a partial close down to the surviving tranche "lands with TRIM support"; it
 * never did, and the 2026-08-05 partial-flatten design named it explicitly as a non-goal. The
 * promise was worse than silence — it invited the next reader to plan around a capability that
 * does not exist. Building it is a design decision, not a gap to be filled in passing.
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
            @Value("${dracul.executor.pending-exit-stale-hours:24}") int pendingExitStaleHours) {
        this(gateway, positionRepo, decisionRepo, cooldownRepo, ruleVersions, mapper, telegram,
                executorNotifier, cooldownDays, pendingExitStaleHours, Clock.systemUTC());
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
        // history endpoint is a separate, slower call, and losing it must degrade reconcile to
        // exactly its previous behaviour (the RECONCILE_GONE path, which recovers real fill
        // prices from closedPositions) rather than abort the whole pass.
        List<BrokerOrder> filledOrders;
        try {
            filledOrders = gateway.filledOrdersSince(connection,
                    clock.instant().minus(Duration.ofHours(FILL_LOOKBACK_HOURS)));
        } catch (RuntimeException e) {
            log.warn("filled-order history unavailable during reconcile ({}); "
                    + "falling back to position-gone detection", e.getMessage());
            filledOrders = List.of();
        }

        // Orphan scan (broker→DB): a live broker position with no open book row means an
        // entry was placed but never persisted (crash / DB failure after placeBracket) —
        // unmanaged capital. Escalate only; NEVER auto-flatten (operator-in-the-loop). Runs on
        // the pre-loop `open` list, so a position about to be closed this run is still "known"
        // (no false orphan).
        for (BrokerPosition bp : brokerPositions) {
            boolean known = open.stream().anyMatch(p -> p.symbol().equals(bp.symbol()));
            if (!known) {
                decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                        "MAINTENANCE", null, null, null, bp.symbol(), null, null,
                        "ESCALATE", "ORPHAN_POSITION", null,
                        "broker position " + bp.symbol() + " has no open book row — unmanaged capital, operator attention required",
                        null, null, null));
                telegram.notifyAlert(bp.symbol(), "ORPHAN_POSITION", "CRITICAL",
                        "broker holds " + bp.symbol() + " with no executor book row — check ORPHANED_ORDER decisions / reconcile manually");
            }
        }

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
            } else if (bp == null || filledLeg != null) {
                closePosition(p, filledLeg, bp, connection, runId);
            } else {
                survivors.add(updateMaintenance(p, bp, runId));
            }
        }
        return new ReconcileResult(survivors, unfilledIds);
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
        // left the book claiming more shares than exist: prod 2026-08-06 had STT booked at 12
        // against 6 held and OFG at 42 against 21, and every quantity-based action — the
        // exit_position flatten remainder, the exposure/heat veto inputs — computed on the
        // phantom half. Idempotent, logs only on an actual change; a null/non-positive broker qty
        // is ignored rather than blanking a good book value.
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
