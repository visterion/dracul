package de.visterion.dracul.depot;

import de.visterion.dracul.executor.DecisionLog;
import de.visterion.dracul.executor.DecisionLogRepository;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DepotHistoryServiceTest {

    private DepotDto depotWithProvider(String provider) {
        return new DepotDto("depot-1", provider, "paper", "ok", null, null, null, null, List.of(), List.of(), null);
    }

    @Test
    void alpacaOrdersAreEnrichedWhenBrokerOrderIdMatches() {
        var client = mock(AgoraDepotClient.class);
        var depotService = mock(DepotService.class);
        var positions = mock(ExecutorPositionRepository.class);
        var decisions = mock(DecisionLogRepository.class);

        when(depotService.depot("depot-1", "u@x", false)).thenReturn(depotWithProvider("alpaca"));
        when(client.orders("depot-1", "all")).thenReturn(List.of(
                new DepotOrder("o-1", "AAPL", "buy", new BigDecimal("10"), "market", "filled", "entry")));
        when(positions.findByBrokerOrderId("o-1")).thenReturn(new ExecutorPosition(
                7L, "depot-1", "AAPL", "buy", new BigDecimal("10"), new BigDecimal("100"), null, null, 1,
                null, List.of("stop below 95"), "sig-1", "index-strigoi", null, null, "CLOSED", "o-1",
                null, null, 0, new BigDecimal("110"), new BigDecimal("2.0"), "TAKE_PROFIT", "2026-01-02",
                null, null, null, null, null, 0, null, null, null, null, null, null));
        when(decisions.findBySignalIdAndAction("sig-1", "ENTER")).thenReturn(new DecisionLog(
                "log-1", "run-1", null, "SIGNAL", "sig-1", "index-strigoi", null, "AAPL",
                null, null, "ENTER", "OK", null, "index inclusion drift", 0.7, null, null));

        var svc = new DepotHistoryService(client, depotService, Optional.of(positions), Optional.of(decisions));
        var out = svc.history("depot-1", "u@x");

        assertThat(out).hasSize(1);
        var e = out.get(0);
        assertThat(e.source()).isEqualTo("ORDER");
        assertThat(e.brokerConfirmed()).isTrue();
        assertThat(e.why()).isNotNull();
        assertThat(e.why().strigoi()).isEqualTo("index-strigoi");
        assertThat(e.why().entryReasoning()).isEqualTo("index inclusion drift");
        assertThat(e.why().draculExitReason()).isEqualTo("TAKE_PROFIT");
    }

    @Test
    void saxoClosedPositionsHaveNoWhy() {
        var client = mock(AgoraDepotClient.class);
        var depotService = mock(DepotService.class);
        when(depotService.depot("depot-1", "u@x", false)).thenReturn(depotWithProvider("saxo"));
        when(client.closedPositions("depot-1")).thenReturn(List.of(
                new DepotClosedPosition("SAP", new BigDecimal("100"), new BigDecimal("120"), new BigDecimal("200"), "cr-1")));

        var svc = new DepotHistoryService(client, depotService, Optional.empty(), Optional.empty());
        var out = svc.history("depot-1", "u@x");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).source()).isEqualTo("CLOSED_POSITION");
        assertThat(out.get(0).profitLoss()).isEqualByComparingTo("200");
        assertThat(out.get(0).why()).isNull();
    }
}
