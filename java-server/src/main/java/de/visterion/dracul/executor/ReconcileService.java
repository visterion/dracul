package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.executor.broker.OrderRole;
import de.visterion.dracul.executor.broker.OrderStatus;
import de.visterion.dracul.notify.TelegramNotifier;
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
 * row OPEN for operator attention. Full multi-leg reconciliation (partial close down to the
 * surviving tranche) lands with TRIM support; until then, capital protection is provided by the
 * broker-held stops, not by this book row.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class ReconcileService {

    private final ExecutionGateway gateway;
    private final ExecutorPositionRepository positionRepo;
    private final DecisionLogRepository decisionRepo;
    private final CooldownRepository cooldownRepo;
    private final RuleVersionProvider ruleVersions;
    private final ObjectMapper mapper;
    private final TelegramNotifier telegram;
    private final int cooldownDays;
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
            @Value("${dracul.executor.cooldown-days:10}") int cooldownDays) {
        this(gateway, positionRepo, decisionRepo, cooldownRepo, ruleVersions, mapper, telegram,
                cooldownDays, Clock.systemUTC());
    }

    ReconcileService(
            ExecutionGateway gateway,
            ExecutorPositionRepository positionRepo,
            DecisionLogRepository decisionRepo,
            CooldownRepository cooldownRepo,
            RuleVersionProvider ruleVersions,
            ObjectMapper mapper,
            TelegramNotifier telegram,
            int cooldownDays,
            Clock clock) {
        this.gateway = gateway;
        this.positionRepo = positionRepo;
        this.decisionRepo = decisionRepo;
        this.cooldownRepo = cooldownRepo;
        this.ruleVersions = ruleVersions;
        this.mapper = mapper;
        this.telegram = telegram;
        this.cooldownDays = cooldownDays;
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

            BrokerOrder filledLeg = findFilledExitLeg(p, orders);

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
                closePosition(p, filledLeg, bp, runId);
            } else {
                survivors.add(updateMaintenance(p, bp));
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

    private BrokerOrder findFilledExitLeg(ExecutorPosition p, List<BrokerOrder> orders) {
        return orders.stream()
                .filter(o -> o.status() == OrderStatus.FILLED)
                .filter(o -> o.role() == OrderRole.STOP_LOSS || o.role() == OrderRole.TAKE_PROFIT)
                .filter(o -> matchesPosition(p, o))
                .findFirst().orElse(null);
    }

    private boolean matchesPosition(ExecutorPosition p, BrokerOrder o) {
        boolean parentMatch = p.brokerOrderId() != null && p.brokerOrderId().equals(o.parentId());
        boolean stopIdMatch = p.stopOrderId() != null && p.stopOrderId().equals(o.orderId());
        boolean parent2Match = p.tranche2OrderId() != null && p.tranche2OrderId().equals(o.parentId());
        boolean stop2Match = p.tranche2StopOrderId() != null && p.tranche2StopOrderId().equals(o.orderId());
        return parentMatch || stopIdMatch || parent2Match || stop2Match;
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

    private void closePosition(ExecutorPosition p, BrokerOrder filledLeg, BrokerPosition bp, String runId) {
        String exitReason;
        BigDecimal exitPrice;
        if (filledLeg != null && filledLeg.role() == OrderRole.STOP_LOSS) {
            exitReason = "HARD_STOP";
            exitPrice = filledLeg.avgFillPrice();
        } else if (filledLeg != null && filledLeg.role() == OrderRole.TAKE_PROFIT) {
            exitReason = "TAKE_PROFIT";
            exitPrice = filledLeg.avgFillPrice();
        } else {
            exitReason = "RECONCILE_GONE";
            exitPrice = null;
        }
        if (exitPrice == null) {
            exitPrice = bp != null ? bp.marketPrice() : p.activeStop();
        }

        BigDecimal realizedR = computeR(p, exitPrice);

        positionRepo.close(p.id(), exitPrice, realizedR, exitReason);
        cooldownRepo.add(p.symbol(), exitReason,
                clock.instant().plus(Duration.ofDays(cooldownDays)), "fresh setup only");

        String action = ("HARD_STOP".equals(exitReason) || "TAKE_PROFIT".equals(exitReason))
                ? "LOG_HARD_EXIT" : "RECONCILE_CLOSE";

        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("exit_price", exitPrice);
        inputs.put("realized_r", realizedR);
        inputs.put("entry_price", p.entryPrice());
        inputs.put("initial_stop", p.initialStop());

        // Exact position linkage for the outcome batch job (decision_log has no position_id
        // column; order_json carries it). A reconcile close is always a full flatten.
        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("fraction", 1.0);
        orderJson.put("position_id", p.id());

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                action, exitReason, orderJson, null, null, null, null));
    }

    private ExecutorPosition updateMaintenance(ExecutorPosition p, BrokerPosition bp) {
        BigDecimal currentClose = bp.marketPrice();
        BigDecimal baseHighest = p.highestPrice() == null ? p.entryPrice() : p.highestPrice();
        // highest_price is the favorable price extreme: highest for a long, lowest for a short.
        BigDecimal newHighest = "SELL".equalsIgnoreCase(p.side())
                ? baseHighest.min(currentClose)
                : baseHighest.max(currentClose);

        BigDecimal currentR = computeR(p, currentClose);
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
                p.trimCount(), p.lowestPrice(), p.entryExpiresAt());
    }

    private BigDecimal computeR(ExecutorPosition p, BigDecimal exitPrice) {
        if (exitPrice == null) return null;
        BigDecimal denominator;
        BigDecimal numerator;
        if ("SELL".equals(p.side())) {
            numerator = p.entryPrice().subtract(exitPrice);
            denominator = p.initialStop().subtract(p.entryPrice());
        } else {
            numerator = exitPrice.subtract(p.entryPrice());
            denominator = p.entryPrice().subtract(p.initialStop());
        }
        if (denominator.compareTo(BigDecimal.ZERO) == 0) return null;
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }
}
