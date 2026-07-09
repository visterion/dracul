package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.FakeExecutionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HardTriggerServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");

    private final FakeExecutionGateway gateway = new FakeExecutionGateway();
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
    private final CooldownRepository cooldownRepo = mock(CooldownRepository.class);
    private final RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private HardTriggerService service;

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-v0.2");
        service = new HardTriggerService(gateway, positionRepo, decisionRepo, cooldownRepo,
                ruleVersions, mapper, 0.35, 1.5, 10, clock);
    }

    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal entry,
            BigDecimal initialStop, BigDecimal activeStop, BigDecimal mfeR) {
        return new ExecutorPosition(id, "c", symbol, side, BigDecimal.TEN, entry, initialStop,
                activeStop, 1, null, List.of(), "sig-1", "agent", "2026-07-01", null, "OPEN",
                "brk-1", null, mfeR, 0, null, null, null, null, "stop-1");
    }

    @Test
    void stopBreach_flattensAndClosesHardStop() {
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), null);

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("94")), "run1");

        assertThat(gateway.flattenedSymbols).containsExactly("ACME");
        assertThat(gateway.flattenFractions).containsExactly(BigDecimal.ONE);

        ArgumentCaptor<BigDecimal> currentRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("94")),
                currentRCaptor.capture(), org.mockito.ArgumentMatchers.eq("HARD_STOP"));
        assertThat(currentRCaptor.getValue()).isEqualByComparingTo("-1.2");

        verify(cooldownRepo).add(org.mockito.ArgumentMatchers.eq("ACME"),
                org.mockito.ArgumentMatchers.eq("HARD_STOP"), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("HARD_TRIGGER");
        assertThat(log.action()).isEqualTo("LOG_HARD_EXIT");
        assertThat(log.reasonCode()).isEqualTo("HARD_STOP");
        assertThat(log.symbol()).isEqualTo("ACME");
        assertThat(log.ruleVersion()).isEqualTo("exec-v0.2");
        assertThat(log.vetoResults().get(0).get("check").asString()).isEqualTo("STOP_BREACH");
        assertThat(log.vetoResults().get(0).get("passed").asBoolean()).isFalse();
        assertThat(log.latency().get("trigger_to_order_seconds").asLong()).isGreaterThanOrEqualTo(0);

        assertThat(survivors).isEmpty();
    }

    @Test
    void giveback_flattens() {
        ExecutorPosition p = openPosition(2L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), new BigDecimal("2.0"));

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("106")), "run1");

        assertThat(gateway.flattenedSymbols).containsExactly("ACME");

        ArgumentCaptor<BigDecimal> currentRCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).close(org.mockito.ArgumentMatchers.eq(2L),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("106")),
                currentRCaptor.capture(), org.mockito.ArgumentMatchers.eq("GIVEBACK_BREACH"));
        assertThat(currentRCaptor.getValue()).isEqualByComparingTo("1.2");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.reasonCode()).isEqualTo("GIVEBACK_BREACH");
        assertThat(log.vetoResults().get(0).get("check").asString()).isEqualTo("GIVEBACK");

        assertThat(survivors).isEmpty();
    }

    @Test
    void noTrigger_survives() {
        ExecutorPosition p = openPosition(3L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), new BigDecimal("2.0"));

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("130")), "run1");

        assertThat(gateway.flattenedSymbols).isEmpty();
        assertThat(survivors).containsExactly(p);
        verify(positionRepo, never()).close(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
    }

    @Test
    void missingPrice_survivesNoAction() {
        ExecutorPosition p = openPosition(4L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), new BigDecimal("2.0"));

        List<ExecutorPosition> survivors = service.apply(List.of(p), Map.of(), "run1");

        assertThat(gateway.flattenedSymbols).isEmpty();
        assertThat(survivors).containsExactly(p);
        verify(positionRepo, never()).close(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
    }

    @Test
    void brokerUnavailableOnFlatten_escalatesKeepsPosition() {
        ExecutorPosition p = openPosition(5L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), null);
        gateway.unavailable = true;

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("94")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("HARD_TRIGGER");
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(log.symbol()).isEqualTo("ACME");

        verify(positionRepo, never()).close(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
        assertThat(survivors).containsExactly(p);
    }
}
