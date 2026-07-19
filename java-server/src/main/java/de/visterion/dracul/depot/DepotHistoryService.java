package de.visterion.dracul.depot;

import de.visterion.dracul.executor.DecisionLog;
import de.visterion.dracul.executor.DecisionLogRepository;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Assembles the Depot history: broker-authoritative facts (via Agora) enriched with Dracul's
 *  optional, non-authoritative "why". Executor repos are optional (only present when
 *  {@code dracul.executor.enabled=true}); without them, facts only. */
@Service
public class DepotHistoryService {

    private static final Set<String> CLOSED_ORDER_STATUSES =
            Set.of("filled", "canceled", "cancelled", "expired", "rejected");

    private final AgoraDepotClient client;
    private final DepotService depotService;
    private final Optional<ExecutorPositionRepository> positions;
    private final Optional<DecisionLogRepository> decisions;
    private final Optional<ExecutorSignalRepository> signals;
    private final int lookbackDays;
    private final Clock clock;

    // @Autowired required: two constructors, so Spring cannot infer which to use. ObjectProvider
    // makes the executor repos optional.
    @Autowired
    public DepotHistoryService(AgoraDepotClient client, DepotService depotService,
            ObjectProvider<ExecutorPositionRepository> positions,
            ObjectProvider<DecisionLogRepository> decisions,
            ObjectProvider<ExecutorSignalRepository> signals,
            @Value("${dracul.depots.history-lookback-days:90}") int lookbackDays) {
        this(client, depotService,
                Optional.ofNullable(positions.getIfAvailable()),
                Optional.ofNullable(decisions.getIfAvailable()),
                Optional.ofNullable(signals.getIfAvailable()),
                lookbackDays, Clock.systemUTC());
    }

    // Test-friendly constructor (inject repos + clock directly).
    DepotHistoryService(AgoraDepotClient client, DepotService depotService,
            Optional<ExecutorPositionRepository> positions, Optional<DecisionLogRepository> decisions,
            Optional<ExecutorSignalRepository> signals, int lookbackDays, Clock clock) {
        this.client = client;
        this.depotService = depotService;
        this.positions = positions;
        this.decisions = decisions;
        this.signals = signals;
        this.lookbackDays = lookbackDays;
        this.clock = clock;
    }

    public List<DepotHistoryEntry> history(String connection, String userEmail) {
        DepotDto depot = depotService.depot(connection, userEmail, false);
        if (depot == null) return List.of();
        String provider = depot.provider() == null ? "" : depot.provider().toLowerCase();

        String to = Instant.now(clock).toString();
        String from = Instant.now(clock).minus(Duration.ofDays(lookbackDays)).toString();

        if (provider.contains("alpaca")) {
            return client.orders(connection, "all", from, to).stream()
                    .filter(o -> o.status() != null && CLOSED_ORDER_STATUSES.contains(o.status().toLowerCase()))
                    .map(this::fromOrder)
                    .toList();
        }
        // Default / Saxo: closed positions, enriched via clientRef -> source_signal_id.
        return client.closedPositions(connection, from, to).stream()
                .map(this::fromClosedPosition)
                .toList();
    }

    private DepotHistoryEntry fromOrder(DepotOrder o) {
        // Alpaca path: link is by broker order id (unchanged behaviour), timestamps from the order.
        DepotHistoryEntry.Why why = enrichByBrokerOrderId(o.brokerOrderId());
        return new DepotHistoryEntry("ORDER", o.symbol(), o.side(), o.qty(), null, null, null,
                o.status(), o.brokerOrderId(), o.submittedAt(), o.filledAt(), o.avgFillPrice(),
                true, why);
    }

    private DepotHistoryEntry fromClosedPosition(DepotClosedPosition c) {
        DepotHistoryEntry.Why why = enrichBySignalId(c.clientRef());
        return new DepotHistoryEntry("CLOSED_POSITION", c.symbol(), null, null,
                c.openPrice(), c.closePrice(), c.profitLoss(), "closed", null,
                c.openTime(), c.closeTime(), null, true, why);
    }

    /** Alpaca link: executor_position by broker order id (unchanged). */
    private DepotHistoryEntry.Why enrichByBrokerOrderId(String brokerOrderId) {
        if (brokerOrderId == null || positions.isEmpty()) return null;
        return whyFor(positions.get().findByBrokerOrderId(brokerOrderId));
    }

    /** Saxo link: clientRef echoes Dracul's signal id (entry = signalId, T2 add-on = "t2-"+signalId). */
    private DepotHistoryEntry.Why enrichBySignalId(String clientRef) {
        if (clientRef == null || positions.isEmpty()) return null;
        ExecutorPosition p = positions.get().findBySourceSignalId(clientRef);
        if (p == null && clientRef.startsWith("t2-")) {
            p = positions.get().findBySourceSignalId(clientRef.substring(3));
        }
        return whyFor(p);
    }

    private DepotHistoryEntry.Why whyFor(ExecutorPosition p) {
        if (p == null) return null;
        String reasoning = null;
        if (decisions.isPresent() && p.sourceSignalId() != null) {
            DecisionLog d = decisions.get().findBySignalIdAndAction(p.sourceSignalId(), "ENTER");
            if (d != null) reasoning = d.reasoning();
        }
        String runId = null;
        if (signals.isPresent() && p.sourceSignalId() != null) {
            runId = signals.get().findRunIdBySignalId(p.sourceSignalId());
        }
        return new DepotHistoryEntry.Why(p.sourceAgent(), p.killCriteria(), reasoning,
                p.exitReason(), p.realizedR(), runId);
    }

    /** Heuristic run_id for an open depot position, linked by symbol (open broker positions
     *  carry no clientRef/order id to join on directly): the open {@code executor_position} for
     *  (connection, symbol) -> its {@code source_signal_id} -> the linked prey's run_id.
     *  Returns {@code null} when the executor repos are disabled, no open position matches, or
     *  the position has no linked run. */
    public String runIdForOpenPosition(String connection, String symbol) {
        if (positions.isEmpty() || signals.isEmpty()) return null;
        ExecutorPosition p = positions.get().findOpenBySymbol(connection, symbol);
        if (p == null || p.sourceSignalId() == null) return null;
        return signals.get().findRunIdBySignalId(p.sourceSignalId());
    }

    /** The move timeline (ENTER/ADD/TRIM/EXIT) for an open depot position: the open
     *  {@code executor_position} for (connection, symbol) -> its {@code source_signal_id} ->
     *  every {@code decision_log} row for that signal, oldest first, each carrying its own
     *  {@code run_id} so the frontend can link each move to its raw executor transcript.
     *  Returns {@code List.of()} when the executor repos are disabled, no open position
     *  matches, or the position has no linked signal. */
    public List<DepotMove> movesForOpenPosition(String connection, String symbol) {
        if (positions.isEmpty() || decisions.isEmpty()) return List.of();
        ExecutorPosition p = positions.get().findOpenBySymbol(connection, symbol);
        if (p == null || p.sourceSignalId() == null) return List.of();
        return decisions.get().findBySignalId(p.sourceSignalId()).stream()
                .map(d -> new DepotMove(d.action(), d.reasonCode(), d.createdAt(), d.runId()))
                .toList();
    }
}
