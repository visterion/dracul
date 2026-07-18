package de.visterion.dracul.depot;

import de.visterion.dracul.executor.DecisionLog;
import de.visterion.dracul.executor.DecisionLogRepository;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Assembles the Depot history: broker-authoritative facts (via Agora) enriched with Dracul's
 *  optional, non-authoritative "why". Executor repos are optional (only present when
 *  {@code dracul.executor.enabled=true}); without them, facts only. */
@Service
public class DepotHistoryService {

    private static final Set<String> CLOSED_ORDER_STATUSES =
            Set.of("filled", "partially_filled", "canceled", "cancelled", "expired", "rejected");

    private final AgoraDepotClient client;
    private final DepotService depotService;
    private final Optional<ExecutorPositionRepository> positions;
    private final Optional<DecisionLogRepository> decisions;

    // Spring picks this constructor; ObjectProvider makes the executor repos optional.
    public DepotHistoryService(AgoraDepotClient client, DepotService depotService,
            ObjectProvider<ExecutorPositionRepository> positions,
            ObjectProvider<DecisionLogRepository> decisions) {
        this(client, depotService,
                Optional.ofNullable(positions.getIfAvailable()),
                Optional.ofNullable(decisions.getIfAvailable()));
    }

    // Test-friendly constructor.
    DepotHistoryService(AgoraDepotClient client, DepotService depotService,
            Optional<ExecutorPositionRepository> positions, Optional<DecisionLogRepository> decisions) {
        this.client = client;
        this.depotService = depotService;
        this.positions = positions;
        this.decisions = decisions;
    }

    public List<DepotHistoryEntry> history(String connection, String userEmail) {
        DepotDto depot = depotService.depot(connection, userEmail, false);
        if (depot == null) return List.of();
        String provider = depot.provider() == null ? "" : depot.provider().toLowerCase();

        if (provider.contains("alpaca")) {
            return client.orders(connection, "all").stream()
                    .filter(o -> o.status() != null && CLOSED_ORDER_STATUSES.contains(o.status().toLowerCase()))
                    .map(this::fromOrder)
                    .toList();
        }
        // Default / Saxo: closed positions (no order id, no timestamp → no why link today).
        return client.closedPositions(connection).stream()
                .map(this::fromClosedPosition)
                .toList();
    }

    private DepotHistoryEntry fromOrder(DepotOrder o) {
        DepotHistoryEntry.Why why = enrich(o.brokerOrderId());
        return new DepotHistoryEntry("ORDER", o.symbol(), o.side(), o.qty(), null, null, null,
                o.status(), o.brokerOrderId(), true, why);
    }

    private DepotHistoryEntry fromClosedPosition(DepotClosedPosition c) {
        return new DepotHistoryEntry("CLOSED_POSITION", c.symbol(), null, null,
                c.openPrice(), c.closePrice(), c.profitLoss(), "closed", null, true, null);
    }

    private DepotHistoryEntry.Why enrich(String brokerOrderId) {
        if (brokerOrderId == null || positions.isEmpty()) return null;
        ExecutorPosition p = positions.get().findByBrokerOrderId(brokerOrderId);
        if (p == null) return null;
        String reasoning = null;
        if (decisions.isPresent() && p.sourceSignalId() != null) {
            DecisionLog d = decisions.get().findBySignalIdAndAction(p.sourceSignalId(), "ENTER");
            if (d != null) reasoning = d.reasoning();
        }
        return new DepotHistoryEntry.Why(p.sourceAgent(), p.killCriteria(), reasoning,
                p.exitReason(), p.realizedR());
    }
}
