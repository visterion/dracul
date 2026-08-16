package de.visterion.dracul.executor;

import de.visterion.dracul.criteria.KillCriteriaEvaluator;
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
    private final KillCriteriaEvaluator killCriteriaEvaluator = new KillCriteriaEvaluator();

    private HardTriggerService service;

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-v0.2");
        service = new HardTriggerService(gateway, positionRepo, decisionRepo, cooldownRepo,
                ruleVersions, mapper, killCriteriaEvaluator, 0.35, 1.5, 10, clock);
    }

    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal entry,
            BigDecimal initialStop, BigDecimal activeStop, BigDecimal mfeR) {
        return openPosition(id, symbol, side, entry, initialStop, activeStop, mfeR, List.of());
    }

    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal entry,
            BigDecimal initialStop, BigDecimal activeStop, BigDecimal mfeR, List<String> killCriteria) {
        return new ExecutorPosition(id, "c", symbol, side, BigDecimal.TEN, entry, initialStop,
                activeStop, 1, null, killCriteria, "sig-1", "agent", "2026-07-01", null, "OPEN",
                "brk-1", null, mfeR, 0, null, null, null, null, "stop-1",
                null, null, null, null, 0, null, null, null, null, null, null, false);
    }

    @Test
    void hardTriggerLeavesPositionOpenPendingExit() {
        // Verified prod incident (PSMT): closing the book right after a flatten is merely
        // *accepted* — not confirmed filled — can book a wrong exit price/R while the broker
        // still holds shares + a working exit order. A hard trigger must stamp a pending-exit
        // marker and leave the row OPEN; only ReconcileService may close it, once confirmed.
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), null);

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("94")), "run1");

        assertThat(gateway.flattenedSymbols).containsExactly("ACME");
        assertThat(gateway.flattenFractions).containsExactly(BigDecimal.ONE);

        verify(positionRepo).markPendingExit(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("HARD_STOP"),
                org.mockito.ArgumentMatchers.eq("close-1"), any(), org.mockito.ArgumentMatchers.eq(NOW));
        verify(positionRepo, never()).close(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
        verify(cooldownRepo, never()).add(any(), any(), any(), any());

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

        verify(positionRepo).markPendingExit(org.mockito.ArgumentMatchers.eq(2L),
                org.mockito.ArgumentMatchers.eq("GIVEBACK_BREACH"),
                org.mockito.ArgumentMatchers.eq("close-1"), any(), org.mockito.ArgumentMatchers.eq(NOW));
        verify(positionRepo, never()).close(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
        verify(cooldownRepo, never()).add(any(), any(), any(), any());

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
        verify(positionRepo, never()).close(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
    }

    @Test
    void missingPrice_survivesNoAction() {
        ExecutorPosition p = openPosition(4L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), new BigDecimal("2.0"));

        List<ExecutorPosition> survivors = service.apply(List.of(p), Map.of(), "run1");

        assertThat(gateway.flattenedSymbols).isEmpty();
        assertThat(survivors).containsExactly(p);
        verify(positionRepo, never()).close(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
    }

    @Test
    void measurablekillCriterionBreachFlattensFully() {
        ExecutorPosition p = openPosition(6L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("10"), null,
                List.of("Close below $40 invalidates the thesis"));

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("39.50")), "run1");

        assertThat(gateway.flattenedSymbols).containsExactly("ACME");
        assertThat(gateway.flattenFractions).containsExactly(BigDecimal.ONE);

        verify(positionRepo).markPendingExit(org.mockito.ArgumentMatchers.eq(6L),
                org.mockito.ArgumentMatchers.eq("HARD_KILL_CRITERIA"),
                org.mockito.ArgumentMatchers.eq("close-1"), any(), org.mockito.ArgumentMatchers.eq(NOW));
        verify(positionRepo, never()).close(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.reasonCode()).isEqualTo("HARD_KILL_CRITERIA");
        assertThat(log.vetoResults().get(0).get("check").asString()).isEqualTo("KILL_CRITERIA");
        String measured = log.vetoResults().get(0).get("measured").asString();
        assertThat(measured).contains("Close below $40 invalidates the thesis");
        assertThat(measured).contains("close 39.5");

        assertThat(survivors).isEmpty();
    }

    @Test
    void stopBreachWinsOverKillCriteria() {
        ExecutorPosition p = openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), null,
                List.of("Close below $40 invalidates the thesis"));

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("39.50")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("HARD_STOP");

        assertThat(survivors).isEmpty();
    }

    @Test
    void killCriteriaWinsOverGiveback() {
        ExecutorPosition p = openPosition(8L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("2.0"),
                List.of("Close below $95 invalidates the thesis"));

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("94")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("HARD_KILL_CRITERIA");

        assertThat(survivors).isEmpty();
    }

    @Test
    void qualitativeCriterionDoesNotTrigger() {
        ExecutorPosition p = openPosition(9L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("10"), null,
                List.of("CEO departs"));

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("39.50")), "run1");

        assertThat(gateway.flattenedSymbols).isEmpty();
        assertThat(survivors).containsExactly(p);
    }

    /** Fixed-instant clock whose time a test can move forward explicitly. */
    private static final class SteppingClock extends Clock {
        private Instant now;

        SteppingClock(Instant start) { this.now = start; }

        void advance(java.time.Duration d) { now = now.plus(d); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(java.time.ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }

    @Test
    void hardExitLatency_spansDetectionToOrder_notPostFlattenOnly() {
        // trigger_to_order_seconds must be anchored BEFORE the flatten call: the gateway here
        // takes 7s (it advances the clock inside flatten), so the logged latency must reflect
        // that span. Anchoring after the flatten would always measure ~0 — a dead metric.
        SteppingClock steppingClock = new SteppingClock(NOW);
        FakeExecutionGateway slowGateway = new FakeExecutionGateway() {
            @Override
            public de.visterion.dracul.executor.broker.CloseResult flatten(
                    String connection, String symbol, BigDecimal fraction) {
                steppingClock.advance(java.time.Duration.ofSeconds(7));
                return super.flatten(connection, symbol, fraction);
            }
        };
        HardTriggerService slowService = new HardTriggerService(slowGateway, positionRepo,
                decisionRepo, cooldownRepo, ruleVersions, mapper, killCriteriaEvaluator,
                0.35, 1.5, 10, steppingClock);

        ExecutorPosition p = openPosition(10L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), null);

        slowService.apply(List.of(p), Map.of("ACME", new BigDecimal("94")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().latency().get("trigger_to_order_seconds").asLong())
                .isEqualTo(7L);
    }

    private List<String> warningsWhile(Class<?> loggerClass, Runnable body) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(loggerClass);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void warnsWhenAPositionSurvivesUnevaluatedBecauseItsCloseIsMissing() {
        ExecutorPosition dark = openPosition(11L, "DARK", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), null);
        // entry=90, stop=85, mfeR=2.0, close=100 -> currentR = (100-90)/(90-85) = 2.0, above the
        // giveback threshold (2.0 * 0.65 = 1.3): GOOD runs through all three detect* branches
        // (stop-breach, kill-criteria, giveback) and survives on its own merits, not because a
        // branch bailed out early on a null mfeR.
        ExecutorPosition ok = openPosition(12L, "GOOD", "BUY", new BigDecimal("90"),
                new BigDecimal("85"), new BigDecimal("85"), new BigDecimal("2.0"));

        var survivors = new java.util.concurrent.atomic.AtomicReference<List<ExecutorPosition>>();
        var warnings = warningsWhile(HardTriggerService.class, () -> survivors.set(
                service.apply(List.of(dark, ok), Map.of("GOOD", new BigDecimal("100")), "run1")));

        // Behaviour is UNCHANGED: the position still survives.
        assertThat(survivors.get()).extracting(ExecutorPosition::symbol)
                .containsExactlyInAnyOrder("DARK", "GOOD");
        // But it is no longer silent that it survived UNEVALUATED. The full message is pinned
        // (isEqualTo, not contains): the format is contract, not incidental wording.
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .isEqualTo("hard trigger skipped: position=11 symbol=DARK — no close price, "
                        + "stop breach and kill criteria NOT evaluated this run");
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

        verify(positionRepo, never()).close(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
        assertThat(survivors).containsExactly(p);
    }
}
