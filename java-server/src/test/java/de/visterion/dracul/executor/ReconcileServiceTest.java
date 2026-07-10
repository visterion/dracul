package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.FakeExecutionGateway;
import de.visterion.dracul.executor.broker.OrderRole;
import de.visterion.dracul.executor.broker.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconcileServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");

    private final FakeExecutionGateway gateway = new FakeExecutionGateway();
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
    private final CooldownRepository cooldownRepo = mock(CooldownRepository.class);
    private final RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private ReconcileService service;

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-v0.2");
        service = new ReconcileService(gateway, positionRepo, decisionRepo, cooldownRepo,
                ruleVersions, mapper, 10, clock);
    }

    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal entry,
            BigDecimal initialStop, String brokerOrderId, String stopOrderId,
            BigDecimal highest, BigDecimal mfeR) {
        return new ExecutorPosition(id, "c", symbol, side, BigDecimal.TEN, entry, initialStop,
                initialStop, 1, null, List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN",
                brokerOrderId, highest, mfeR, 0, null, null, null, null, stopOrderId,
                null, null, null, null);
    }

    @Test
    void stopLegFilled_closesWithHardStopAndRealizedR() {
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-1", "stop-1", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("stop-1", "ref-1", "ACME", OrderRole.STOP_LOSS,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("95"), "brk-1"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1");

        ArgumentCaptor<BigDecimal> exitPriceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(1L), exitPriceCaptor.capture(), realizedRCaptor.capture(),
                eq("HARD_STOP"));
        assertThat(exitPriceCaptor.getValue()).isEqualByComparingTo("95");
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("-1.0");

        ArgumentCaptor<Instant> expiryCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(cooldownRepo).add(eq("ACME"), eq("HARD_STOP"), expiryCaptor.capture(), any());
        assertThat(expiryCaptor.getValue()).isEqualTo(NOW.plus(java.time.Duration.ofDays(10)));

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");
        assertThat(log.action()).isEqualTo("LOG_HARD_EXIT");
        assertThat(log.reasonCode()).isEqualTo("HARD_STOP");
        assertThat(log.symbol()).isEqualTo("ACME");
        assertThat(log.ruleVersion()).isEqualTo("exec-v0.2");

        assertThat(survivors).isEmpty();
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void takeProfitLegFilled_closesTakeProfit() {
        ExecutorPosition p = openPosition(2L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-2", "stop-2", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedOrder(new BrokerOrder("tp-2", "ref-2", "ACME", OrderRole.TAKE_PROFIT,
                OrderStatus.FILLED, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("112"), "brk-2"));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1");

        ArgumentCaptor<BigDecimal> realizedRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(eq(2L), eq(new BigDecimal("112")), realizedRCaptor.capture(),
                eq("TAKE_PROFIT"));
        assertThat(realizedRCaptor.getValue()).isEqualByComparingTo("2.4");

        verify(cooldownRepo).add(eq("ACME"), eq("TAKE_PROFIT"), any(), any());
        assertThat(survivors).isEmpty();
    }

    @Test
    void stillOpen_updatesHighestAndMfe() {
        ExecutorPosition p = openPosition(3L, "BBB", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-3", "stop-3", new BigDecimal("100"), BigDecimal.ZERO);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("BBB", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("108")));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1");

        ArgumentCaptor<BigDecimal> highestCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> mfeCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateMaintenance(eq(3L), highestCaptor.capture(), mfeCaptor.capture(),
                eq(0), eq(new BigDecimal("95")), eq(null));
        assertThat(highestCaptor.getValue()).isEqualByComparingTo("108");
        assertThat(mfeCaptor.getValue()).isEqualByComparingTo("1.6");

        verify(positionRepo, never()).close(anyLong(), any(), any(), any());

        assertThat(survivors).hasSize(1);
        ExecutorPosition survivor = survivors.get(0);
        assertThat(survivor.symbol()).isEqualTo("BBB");
        assertThat(survivor.highestPrice()).isEqualByComparingTo("108");
        assertThat(survivor.mfeR()).isEqualByComparingTo("1.6");
    }

    @Test
    void stillOpenShort_favorableExtremeIsMinimum() {
        ExecutorPosition p = openPosition(5L, "SHORT1", "SELL", new BigDecimal("100"),
                new BigDecimal("105"), "brk-5", "stop-5", new BigDecimal("100"), BigDecimal.ZERO);
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("SHORT1", "SELL", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("94")));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1");

        ArgumentCaptor<BigDecimal> highestCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> mfeCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateMaintenance(eq(5L), highestCaptor.capture(), mfeCaptor.capture(),
                eq(0), eq(new BigDecimal("105")), eq(null));
        assertThat(highestCaptor.getValue()).isEqualByComparingTo("94");
        assertThat(mfeCaptor.getValue()).isEqualByComparingTo("1.2");

        assertThat(survivors).hasSize(1);
        ExecutorPosition survivor = survivors.get(0);
        assertThat(survivor.highestPrice()).isEqualByComparingTo("94");
        assertThat(survivor.mfeR()).isEqualByComparingTo("1.2");
    }

    @Test
    void stillOpenShort_adverseMoveKeepsPriorFavorableExtreme() {
        ExecutorPosition p = openPosition(6L, "SHORT2", "SELL", new BigDecimal("100"),
                new BigDecimal("105"), "brk-6", "stop-6", new BigDecimal("98"), new BigDecimal("0.4"));
        when(positionRepo.findOpen()).thenReturn(List.of(p));

        gateway.seedPosition(new BrokerPosition("SHORT2", "SELL", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("103")));

        List<ExecutorPosition> survivors = service.reconcile("c", "run1");

        ArgumentCaptor<BigDecimal> highestCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> mfeCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateMaintenance(eq(6L), highestCaptor.capture(), mfeCaptor.capture(),
                eq(0), eq(new BigDecimal("105")), eq(null));
        // favorable extreme (the low) must not move against the position when price rises
        assertThat(highestCaptor.getValue()).isEqualByComparingTo("98");
        // mfeR keeps the best-ever R, not the current (worse) R
        assertThat(mfeCaptor.getValue()).isEqualByComparingTo("0.4");

        assertThat(survivors).hasSize(1);
        assertThat(survivors.get(0).highestPrice()).isEqualByComparingTo("98");
    }

    @Test
    void brokerUnavailable_escalatesAndReturnsUnchanged() {
        ExecutorPosition p = openPosition(4L, "CCC", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), "brk-4", "stop-4", null, null);
        when(positionRepo.findOpen()).thenReturn(List.of(p));
        gateway.unavailable = true;

        List<ExecutorPosition> survivors = service.reconcile("c", "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(log.symbol()).isNull();

        verify(positionRepo, never()).close(anyLong(), any(), any(), any());
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), anyInt(), any(), any());

        assertThat(survivors).isEqualTo(List.of(p));
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
