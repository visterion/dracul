package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.executor.broker.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.List;

/**
 * Enforces the entry good-till-date rule: an unfilled GTD limit bracket is cancelled once its
 * {@code entry_expires_at} has passed — never re-priced. Mirrors {@link ReconcileService}'s and
 * {@link HardTriggerService}'s idiom for gateway/repo wiring and decision-log construction.
 *
 * <p>Order state is read from the same source {@link ReconcileService} uses
 * ({@link ExecutionGateway#orders}), matched to the position's entry order by
 * {@code brokerOrderId}. {@code WORKING} (nothing filled) cancels the whole order and the
 * position; {@code PARTIALLY_FILLED} cancels only the unfilled remainder and leaves the position
 * OPEN (reconcile owns the resulting quantity); {@code FILLED} or an order that can no longer be
 * found (status unavailable) is left untouched this run.
 *
 * <p>On {@link BrokerUnavailableException} this deliberately does nothing to the book — a
 * transient broker outage must never be mistaken for a cancellable order — and escalates via the
 * decision log instead, mirroring {@link ReconcileService}'s/{@link HardTriggerService}'s
 * broker-unavailable idiom.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class EntryExpiryService {

    private final ExecutionGateway gateway;
    private final ExecutorPositionRepository positionRepo;
    private final ExecutorSignalRepository signalRepo;
    private final DecisionLogRepository decisionRepo;
    private final RuleVersionProvider ruleVersions;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public EntryExpiryService(
            ExecutionGateway gateway,
            ExecutorPositionRepository positionRepo,
            ExecutorSignalRepository signalRepo,
            DecisionLogRepository decisionRepo,
            RuleVersionProvider ruleVersions,
            ObjectMapper mapper) {
        this(gateway, positionRepo, signalRepo, decisionRepo, ruleVersions, mapper, Clock.systemUTC());
    }

    EntryExpiryService(
            ExecutionGateway gateway,
            ExecutorPositionRepository positionRepo,
            ExecutorSignalRepository signalRepo,
            DecisionLogRepository decisionRepo,
            RuleVersionProvider ruleVersions,
            ObjectMapper mapper,
            Clock clock) {
        this.gateway = gateway;
        this.positionRepo = positionRepo;
        this.signalRepo = signalRepo;
        this.decisionRepo = decisionRepo;
        this.ruleVersions = ruleVersions;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void expire(String connection, String runId) {
        List<ExecutorPosition> candidates = positionRepo.findOpenUnfilledPastExpiry(clock.instant()).stream()
                .filter(p -> connection.equals(p.connection()))
                .toList();
        if (candidates.isEmpty()) return;

        List<BrokerOrder> orders;
        try {
            orders = gateway.orders(connection);
        } catch (BrokerUnavailableException e) {
            for (ExecutorPosition p : candidates) escalateBrokerUnavailable(p, runId, e);
            return;
        }

        for (ExecutorPosition p : candidates) {
            expireOne(p, orders, runId);
        }
    }

    private void expireOne(ExecutorPosition p, List<BrokerOrder> orders, String runId) {
        BrokerOrder entryOrder = orders.stream()
                .filter(o -> p.brokerOrderId() != null && p.brokerOrderId().equals(o.orderId()))
                .findFirst().orElse(null);

        // Status unavailable (order no longer reported) or already FILLED — do nothing this run.
        if (entryOrder == null || entryOrder.status() == OrderStatus.FILLED) return;

        if (entryOrder.status() == OrderStatus.WORKING) {
            cancelFully(p, entryOrder, runId);
        } else if (entryOrder.status() == OrderStatus.PARTIALLY_FILLED) {
            cancelRemainder(p, entryOrder, runId);
        }
        // CANCELLED / REJECTED: already terminal at the broker, nothing to reconcile here.
    }

    private void cancelFully(ExecutorPosition p, BrokerOrder entryOrder, String runId) {
        try {
            gateway.cancelOrder(p.connection(), entryOrder.orderId());
        } catch (BrokerUnavailableException e) {
            escalateBrokerUnavailable(p, runId, e);
            return;
        }

        positionRepo.markCancelled(p.id());
        if (p.sourceSignalId() != null) {
            signalRepo.markStatus(p.sourceSignalId(), "EXPIRED");
        }
        logCancelExpired(p, runId, false);
    }

    private void cancelRemainder(ExecutorPosition p, BrokerOrder entryOrder, String runId) {
        try {
            gateway.cancelOrder(p.connection(), entryOrder.orderId());
        } catch (BrokerUnavailableException e) {
            escalateBrokerUnavailable(p, runId, e);
            return;
        }

        // Position stays OPEN with its (reconciled) qty — reconcile owns book quantity, this
        // service only ever cancels, never re-prices or re-sizes.
        logCancelExpired(p, runId, true);
    }

    private void logCancelExpired(ExecutorPosition p, String runId, boolean partial) {
        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("partial", partial);

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", p.sourceSignalId(), p.sourceAgent(), null, p.symbol(), null, null,
                "CANCEL_EXPIRED", "SIGNAL_EXPIRED", orderJson, null, null, null, null));
    }

    private void escalateBrokerUnavailable(ExecutorPosition p, String runId, BrokerUnavailableException e) {
        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", p.sourceSignalId(), p.sourceAgent(), null, p.symbol(), null, null,
                "ESCALATE", "BROKER_UNAVAILABLE", null,
                "broker unavailable during entry expiry: " + e.getMessage(), null, null, null));
    }
}
