package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
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
import static org.mockito.ArgumentMatchers.isNull;
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
    private final PartialExitService partialExit = mock(PartialExitService.class);
    private final de.visterion.dracul.notify.TelegramNotifier telegram =
            mock(de.visterion.dracul.notify.TelegramNotifier.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private HardTriggerService service;

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-v0.2");
        service = new HardTriggerService(gateway, positionRepo, decisionRepo, cooldownRepo,
                ruleVersions, mapper, 0.35, 1.5, 10, partialExit, ConvictionProfile.defaults(), telegram, clock);
    }

    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal entry,
            BigDecimal initialStop, BigDecimal activeStop, BigDecimal mfeR) {
        return openPosition(id, symbol, side, entry, initialStop, activeStop, mfeR, List.of());
    }

    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal entry,
            BigDecimal initialStop, BigDecimal activeStop, BigDecimal mfeR, List<String> killCriteria) {
        return ExecutorPositionFixtures.withoutKillLevel(id, "c", symbol, side, BigDecimal.TEN, entry, initialStop,
                activeStop, 1, null, killCriteria, "sig-1", "agent", "2026-07-01", null, "OPEN",
                "brk-1", null, mfeR, 0, null, null, null, null, "stop-1",
                null, null, null, null, 0, null, null, null, null, null, null, false, null, null);
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

    /** {@link #openPosition} carrying a structured kill level. */
    private ExecutorPosition withLevel(ExecutorPosition p, String level) {
        return ExecutorPositionFixtures.withKillLevel(p, new BigDecimal(level), null);
    }

    @Test
    void killLevelBreachFlattensFully() {
        ExecutorPosition p = withLevel(openPosition(6L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("10"), null), "40");

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
        assertThat(log.vetoResults().get(0).get("measured").asString())
                .isEqualTo("KILL_LEVEL: close 39.5 < kill_close_below 40");

        assertThat(survivors).isEmpty();
    }

    @Test
    void stopBreachWinsOverKillLevel() {
        ExecutorPosition p = withLevel(openPosition(7L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), null), "40");

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("39.50")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("HARD_STOP");

        assertThat(survivors).isEmpty();
    }

    @Test
    void killLevelWinsOverGiveback() {
        ExecutorPosition p = withLevel(openPosition(8L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("2.0")), "95");

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

    @Test
    void closeExactlyAtOrAboveTheLevelDoesNotTrigger() {
        ExecutorPosition at = withLevel(openPosition(20L, "ATCO", "BUY", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("10"), null), "40");
        ExecutorPosition above = withLevel(openPosition(21L, "ABOVE", "BUY", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("10"), null), "40");

        List<ExecutorPosition> survivors = service.apply(List.of(at, above),
                Map.of("ATCO", new BigDecimal("40.00"), "ABOVE", new BigDecimal("40.01")), "run1");

        assertThat(gateway.flattenedSymbols).isEmpty();
        assertThat(survivors).containsExactly(at, above);
    }

    @Test
    void sellPositionIgnoresItsKillLevel() {
        // SELL: stop 200 sits above the close, so no stop breach; a BUY would fire at 110 < 120.
        ExecutorPosition p = withLevel(openPosition(22L, "SHRT", "SELL", new BigDecimal("100"),
                new BigDecimal("200"), new BigDecimal("200"), null), "120");

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("SHRT", new BigDecimal("110")), "run1");

        assertThat(gateway.flattenedSymbols).isEmpty();
        assertThat(survivors).containsExactly(p);
    }

    /** The behavior change of spec 2026-10-02: free text is never parsed — not the English shape
     *  the old regex understood, not the German shape every producer actually writes. */
    @Test
    void freeTextPriceCriteriaWithoutAStructuredLevelNeverTrigger() {
        ExecutorPosition english = openPosition(23L, "ENGL", "BUY", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("10"), null,
                List.of("Close below $40 invalidates the thesis"));
        ExecutorPosition german = openPosition(24L, "GERM", "BUY", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("10"), null,
                List.of("Schlusskurs unter 40,00 USD"));

        List<ExecutorPosition> survivors = service.apply(List.of(english, german),
                Map.of("ENGL", new BigDecimal("39.50"), "GERM", new BigDecimal("39.50")), "run1");

        assertThat(gateway.flattenedSymbols).isEmpty();
        assertThat(survivors).containsExactly(english, german);
    }

    @Test
    void logsOneKillLevelCounterLinePerRun() {
        ExecutorPosition breached = withLevel(openPosition(25L, "BRCH", "BUY", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("10"), null), "40");
        ExecutorPosition holding = withLevel(openPosition(26L, "HOLD", "BUY", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("10"), null), "40");
        ExecutorPosition noLevel = openPosition(27L, "NOLV", "BUY", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("10"), null);

        var infos = linesWhile(HardTriggerService.class, ch.qos.logback.classic.Level.INFO,
                () -> service.apply(List.of(breached, holding, noLevel), Map.of(
                        "BRCH", new BigDecimal("39.50"), "HOLD", new BigDecimal("50"),
                        "NOLV", new BigDecimal("50")), "run1"));

        assertThat(infos).containsExactly("kill levels evaluated: 2 of 3 filled positions (breached: 1); catastrophe flagged: 0, targets hit: 0, rebalance exits: 0");
    }

    private List<String> linesWhile(Class<?> loggerClass, ch.qos.logback.classic.Level level,
            Runnable body) {
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
                .filter(e -> e.getLevel() == level)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
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
                decisionRepo, cooldownRepo, ruleVersions, mapper,
                0.35, 1.5, 10, partialExit, ConvictionProfile.defaults(), telegram, steppingClock);

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

    @Test
    void flattenOnVanishedPosition_escalatesAsAlreadyGone() {
        // Real incident (2026-08-24, RGNX): the broker had long since stopped the position out,
        // but the book still held it OPEN. The flatten call correctly reaches the broker and gets
        // an explicit verdict back -- "no open position" -- which is not an outage and must not be
        // filed as one (BUG family this task closes). NO_POSITION is the reject code Agora's
        // FlattenTool actually emits for this definite case (SaxoBrokerProvider.resolveNetPosition
        // -> FlattenTool's NO_POSITION mapping) -- not a code invented for this test. Distinct
        // (fix round 2) from the generic NOT_FOUND, which a 404 elsewhere in flatten also emits
        // and must NOT be treated as "already gone" -- see flattenOnGenericNotFound_isBrokerRejected.
        ExecutorPosition p = openPosition(5L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), null);
        gateway.rejectFlattenWith = new BrokerRejectedException(
                "agora order rejected [NO_POSITION]: no open position: ACME", "NO_POSITION", List.of());

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("94")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("POSITION_ALREADY_GONE");
        assertThat(log.reasonCode()).isNotEqualTo("BROKER_UNAVAILABLE");
        // The full wording is pinned, not just the code: Task 5 shipped a null-interpolating
        // sentence past a reason-code-only test, so this one asserts the exact text and that it
        // never claims an outage or interpolates a null.
        assertThat(log.reasoning()).isEqualTo("position already gone during hard-trigger flatten: "
                + "agora order rejected [NO_POSITION]: no open position: ACME");
        assertThat(log.reasoning()).doesNotContain("null");
        assertThat(log.reasoning()).doesNotContain("unavailable");

        verify(positionRepo, never()).close(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
        assertThat(survivors).containsExactly(p);
    }

    @Test
    void flattenRejectedWithSomeOtherCode_escalatesAsBrokerRejected() {
        // Genuinely reachable on a full flatten: a soft exit already placed a closing order for
        // (at least) the requested size, then a hard trigger fires on the SAME position in a
        // later run and tries to flatten it again -- SaxoBrokerProvider.flatten rejects that with
        // CLOSE_ALREADY_PENDING (an ordinary sequence, not a contrived code). Still a verdict,
        // not an outage, and not the "position already gone" case either -- a third, honestly
        // named bucket.
        ExecutorPosition p = openPosition(5L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), null);
        gateway.rejectFlattenWith = new BrokerRejectedException(
                "agora order rejected [CLOSE_ALREADY_PENDING]: a close of >= the requested size "
                        + "is already working", "CLOSE_ALREADY_PENDING", List.of());

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("94")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("BROKER_REJECTED");
        assertThat(log.reasonCode()).isNotEqualTo("BROKER_UNAVAILABLE");
        assertThat(log.reasonCode()).isNotEqualTo("POSITION_ALREADY_GONE");
        // One reason_code covering every rejection is only queryable if the code that
        // distinguishes them is a FIELD, not prose -- same shape ExecutorWebhookController writes
        // for the identical broker event.
        assertThat(log.inputsSnapshot().path("reject_code").asString())
                .isEqualTo("CLOSE_ALREADY_PENDING");
        assertThat(log.reasoning()).isEqualTo("broker rejected hard-trigger flatten "
                + "[CLOSE_ALREADY_PENDING]: agora order rejected [CLOSE_ALREADY_PENDING]: a close "
                + "of >= the requested size is already working");
        assertThat(log.reasoning()).doesNotContain("null");
        assertThat(log.reasoning()).doesNotContain("already gone");

        verify(positionRepo, never()).close(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
        assertThat(survivors).containsExactly(p);
    }

    @Test
    void flattenOnGenericNotFound_isBrokerRejectedNotAlreadyGone() {
        // Fix round 2: a generic NOT_FOUND (an HTTP 404 on some OTHER read/write inside Agora's
        // flatten -- e.g. the closing POST of a partial close hitting safeWriteError) must NOT be
        // folded into POSITION_ALREADY_GONE the way this task's first round wrongly would have --
        // NOT_FOUND says nothing about whether the position exists, unlike the narrower
        // NO_POSITION code asserted above.
        ExecutorPosition p = openPosition(5L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), null);
        gateway.rejectFlattenWith = new BrokerRejectedException(
                "agora order rejected [NOT_FOUND]: Resource not found (HTTP 404)",
                "NOT_FOUND", List.of());

        service.apply(List.of(p), Map.of("ACME", new BigDecimal("94")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.reasonCode()).isEqualTo("BROKER_REJECTED");
        assertThat(log.reasonCode()).isNotEqualTo("POSITION_ALREADY_GONE");
        // Full text pinned, not just a substring check (fix round 3: a reason-code-plus-
        // doesNotContain test is the same shape that let Task 5's null-interpolating sentence
        // ship green).
        assertThat(log.reasoning()).isEqualTo("broker rejected hard-trigger flatten [NOT_FOUND]: "
                + "agora order rejected [NOT_FOUND]: Resource not found (HTTP 404)");
        assertThat(log.reasoning()).doesNotContain("null");
        assertThat(log.reasoning()).doesNotContain("already gone");
    }

    /** {@link #openPosition} with an explicit broker stop below the logical one. */
    private ExecutorPosition withBrokerStop(ExecutorPosition p, BigDecimal brokerStop) {
        return new ExecutorPosition(p.id(), p.connection(), p.symbol(), p.side(), p.qty(),
                p.entryPrice(), p.initialStop(), p.activeStop(), p.tranche(), p.rValue(),
                p.killCriteria(), p.sourceSignalId(), p.sourceAgent(), p.entryDate(), p.mfe(),
                p.status(), p.brokerOrderId(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                p.exitPrice(), p.realizedR(), p.exitReason(), p.closedAt(), p.stopOrderId(),
                p.sector(), p.entryDayHigh(), p.tranche2OrderId(), p.tranche2StopOrderId(),
                p.trimCount(), p.lowestPrice(), p.entryExpiresAt(), p.submittedLimitPrice(),
                p.pendingExitReason(), p.exitOrderId(), p.pendingExitFillPrice(),
                p.stopLegsCollapsed(), brokerStop, p.entryFilledAt(), p.killCloseBelow(), p.killCloseBelowDropped(),
                p.exitProfile(), p.catastropheReason(), p.catastropheFlaggedAt(),
                p.pendingTrimOrderId(), p.brokerStopNarrow(),
                p.rebalanceExitAt());
    }

    /** Test 22. The hard trigger is the DECISION and it is close-based against active_stop; the
     *  broker leg is only a catastrophe backstop. A close between the two stops must still breach.
     *  Mutation: compare against brokerStop — this close (94) sits ABOVE it (93), so the mutated
     *  rule would hold a position the design says to exit. */
    @Test
    void closeBelowActiveStopButAboveBrokerStopStillBreaches() {
        ExecutorPosition p = withBrokerStop(openPosition(1L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), null), new BigDecimal("93"));

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("94")), "run1");

        assertThat(survivors).isEmpty();
        assertThat(gateway.flattenedSymbols).containsExactly("ACME");
        verify(positionRepo).markPendingExit(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("HARD_STOP"), any(), any(),
                org.mockito.ArgumentMatchers.eq(NOW));
    }

    /** Test 22, the other side: a close between the two stops on a SELL. */
    @Test
    void closeAboveActiveStopButBelowBrokerStopStillBreachesOnSell() {
        ExecutorPosition p = withBrokerStop(openPosition(2L, "ACME", "SELL", new BigDecimal("100"),
                new BigDecimal("105"), new BigDecimal("105"), null), new BigDecimal("107"));

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("106")), "run1");

        assertThat(survivors).isEmpty();
        assertThat(gateway.flattenedSymbols).containsExactly("ACME");
    }

    /** And a close ABOVE both stops still holds — the regression must not simply always breach. */
    @Test
    void closeAboveBothStopsStillHolds() {
        ExecutorPosition p = withBrokerStop(openPosition(3L, "ACME", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), null), new BigDecimal("93"));

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("ACME", new BigDecimal("96")), "run1");

        assertThat(survivors).hasSize(1);
        assertThat(gateway.flattenedSymbols).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // Exit profile CONVICTION (spec 2026-10-03 §5.4)
    // ---------------------------------------------------------------------------------------

    /** CONVICTION BUY, entry 100, emergency stop 65, qty 10. */
    private ExecutorPosition conviction(long id, String symbol, int trimCount, String catastrophe,
            String pendingTrim, BigDecimal mfeR) {
        ExecutorPosition base = ExecutorPositionFixtures.withoutKillLevel(id, "c", symbol, "BUY",
                BigDecimal.TEN, new BigDecimal("100"), new BigDecimal("65"), new BigDecimal("65"),
                1, null, List.of(), "sig-" + id, "strigoi-tech", "2026-07-01", null, "OPEN",
                "brk-" + id, null, mfeR, 0, null, null, null, null, "stop-" + id, null, null, null,
                null, trimCount, null, null, null, null, null, null, false, new BigDecimal("80"),
                "2026-07-01T09:00:00Z");
        return ExecutorPositionFixtures.withProfileFields(base, ExitProfile.CONVICTION,
                catastrophe, catastrophe == null ? null : "2026-07-07 22:30:00+00", pendingTrim, false);
    }

    private DecisionLog onlyRow() {
        ArgumentCaptor<DecisionLog> c = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(c.capture());
        return c.getValue();
    }

    /** P1 #5 (†): CATASTROPHE needs no price — a halt/delisting (no close) is exactly its case.
     *  Full flatten, close and current_r recorded as null, no computeR. */
    @Test
    void catastropheWithoutACloseFlattensFully() {
        ExecutorPosition p = conviction(30L, "SYNA", 0, "synthetic fraud finding", null, null);

        List<ExecutorPosition> survivors = service.apply(List.of(p), Map.of(), "run1");

        assertThat(survivors).isEmpty();
        assertThat(gateway.flattenedSymbols).containsExactly("SYNA");
        assertThat(gateway.flattenFractions).containsExactly(BigDecimal.ONE);
        verify(positionRepo).markPendingExit(org.mockito.ArgumentMatchers.eq(30L),
                org.mockito.ArgumentMatchers.eq("HARD_CATASTROPHE"), any(), any(),
                org.mockito.ArgumentMatchers.eq(NOW));
        DecisionLog row = onlyRow();
        assertThat(row.reasonCode()).isEqualTo("HARD_CATASTROPHE");
        assertThat(row.inputsSnapshot().path("close").isNull()).isTrue();
        assertThat(row.inputsSnapshot().path("current_r").isNull()).isTrue();
        assertThat(row.vetoResults().get(0).path("check").asString()).isEqualTo("CATASTROPHE");
        assertThat(row.vetoResults().get(0).path("measured").asString())
                .isEqualTo("CATASTROPHE: synthetic fraud finding");
    }

    /** P1 #4: catastrophe + stop breach the same night -> HARD_CATASTROPHE only. */
    @Test
    void catastropheWinsOverTheStop() {
        ExecutorPosition p = conviction(31L, "SYNB", 0, "synthetic export ban", null, null);

        service.apply(List.of(p), Map.of("SYNB", new BigDecimal("60")), "run1");

        assertThat(gateway.flattenedSymbols).containsExactly("SYNB");
        assertThat(onlyRow().reasonCode()).isEqualTo("HARD_CATASTROPHE");
    }

    /** P1 #4: a pending trim does not shield a catastrophe — full flatten. */
    @Test
    void catastropheWithAPendingTrimFlattensFully() {
        ExecutorPosition p = conviction(32L, "SYNC", 1, "synthetic restatement", "trim-1", null);

        service.apply(List.of(p), Map.of("SYNC", new BigDecimal("120")), "run1");

        assertThat(gateway.flattenFractions).containsExactly(BigDecimal.ONE);
        verify(partialExit, never()).execute(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** P1 #3: close exactly entry x 1.30 fires TARGET_HALF (fraction 0.5 via PartialExitService);
     *  the position stays a survivor. */
    @Test
    void targetHalfFiresAtExactlyThirtyPercent() {
        ExecutorPosition p = conviction(33L, "SYND", 0, null, null, null);

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("SYND", new BigDecimal("130.00")), "run1");

        assertThat(survivors).containsExactly(p);
        assertThat(gateway.flattenedSymbols).isEmpty();
        verify(partialExit).execute(org.mockito.ArgumentMatchers.eq(p),
                org.mockito.ArgumentMatchers.argThat(f -> f.compareTo(new BigDecimal("0.5")) == 0),
                org.mockito.ArgumentMatchers.eq("HARD_TRIGGER"),
                org.mockito.ArgumentMatchers.eq("HARD_TARGET_HALF"),
                org.mockito.ArgumentMatchers.eq("target-half flatten"), any(), isNull(),
                org.mockito.ArgumentMatchers.eq("run1"));
    }

    /** P1 #3: one tick below the target does not fire. */
    @Test
    void oneTickBelowTheTargetDoesNotFire() {
        ExecutorPosition p = conviction(34L, "SYNE", 0, null, null, null);

        service.apply(List.of(p), Map.of("SYNE", new BigDecimal("129.99")), "run1");

        verify(partialExit, never()).execute(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** P1 #3/#4: exactly once — trim_count 1 (night 2, or the second maintenance pass of the same
     *  run, which re-reads the trimmed row) and a pending trim both block a second half-sale. */
    @Test
    void targetHalfFiresExactlyOnce() {
        ExecutorPosition halfSold = conviction(35L, "SYNF", 1, null, null, null);
        ExecutorPosition pending = conviction(36L, "SYNG", 0, null, "trim-36", null);

        service.apply(List.of(halfSold, pending), Map.of("SYNF", new BigDecimal("150"),
                "SYNG", new BigDecimal("150")), "run1");

        verify(partialExit, never()).execute(any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(gateway.flattenedSymbols).isEmpty();
    }

    /** P1 #4 precedence: the stop still wins over the target for a CONVICTION row (a stop breach
     *  and a target cannot both hold for one close, but the order is pinned). */
    @Test
    void convictionStopBreachFlattensWithHardStop() {
        ExecutorPosition p = conviction(37L, "SYNH", 0, null, null, null);

        service.apply(List.of(p), Map.of("SYNH", new BigDecimal("64.99")), "run1");

        assertThat(onlyRow().reasonCode()).isEqualTo("HARD_STOP");
    }

    /** Spec §5.4 #3: kill level and giveback are STANDARD only. */
    @Test
    void convictionIgnoresKillLevelAndGiveback() {
        ExecutorPosition p = ExecutorPositionFixtures.withKillLevel(
                conviction(38L, "SYNI", 1, null, null, new BigDecimal("2.0")), new BigDecimal("110"), null);

        service.apply(List.of(p), Map.of("SYNI", new BigDecimal("101")), "run1");

        assertThat(gateway.flattenedSymbols).isEmpty();
        verify(decisionRepo, never()).insert(any());
    }

    /** STANDARD regression: +30 % never triggers a half-sale; giveback still fires. */
    @Test
    void standardHasNoTargetHalfAndKeepsGiveback() {
        ExecutorPosition runner = openPosition(39L, "SYNJ", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), null);
        ExecutorPosition givingBack = openPosition(40L, "SYNK", "BUY", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("95"), new BigDecimal("2.0"));

        service.apply(List.of(runner, givingBack), Map.of("SYNJ", new BigDecimal("130"),
                "SYNK", new BigDecimal("106")), "run1");

        verify(partialExit, never()).execute(any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(gateway.flattenedSymbols).containsExactly("SYNK");
    }

    @Test
    void infoLineCountsCatastrophesAndTargets() {
        ExecutorPosition flagged = conviction(41L, "SYNL", 0, "synthetic delisting", null, null);
        ExecutorPosition target = conviction(42L, "SYNM", 0, null, null, null);

        var infos = linesWhile(HardTriggerService.class, ch.qos.logback.classic.Level.INFO,
                () -> service.apply(List.of(flagged, target), Map.of("SYNL", new BigDecimal("90"),
                        "SYNM", new BigDecimal("131")), "run1"));

        assertThat(infos).containsExactly("kill levels evaluated: 0 of 2 filled positions "
                + "(breached: 0); catastrophe flagged: 1, targets hit: 1, rebalance exits: 0");
    }

    /** Final review #1: a TARGET_HALF call that got no verdict (e.g. a read timeout after the POST
     *  was sent) may still have sold half at the broker. trim_count is bumped without touching qty,
     *  an ESCALATE TARGET_HALF_UNCONFIRMED row names the position, a CRITICAL alert asks for a
     *  broker check — and the next pass, re-reading trim_count 1, never sells a second half. */
    @Test
    void targetHalfUnavailableIsMarkedUnconfirmedAndNeverRetried() {
        ExecutorPosition p = conviction(43L, "SYNN", 0, null, null, null);
        when(partialExit.execute(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PartialExitService.Result(PartialExitService.Outcome.UNAVAILABLE,
                        null, null, null, null));

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("SYNN", new BigDecimal("131")), "run1");

        assertThat(survivors).containsExactly(p);
        verify(positionRepo).markTrimUnconfirmed(43L, 1);
        verify(positionRepo, never()).recordTrim(org.mockito.ArgumentMatchers.anyLong(), any(),
                org.mockito.ArgumentMatchers.anyInt());
        DecisionLog row = onlyRow();
        assertThat(row.action()).isEqualTo("ESCALATE");
        assertThat(row.reasonCode()).isEqualTo("TARGET_HALF_UNCONFIRMED");
        assertThat(row.triggerType()).isEqualTo("HARD_TRIGGER");
        assertThat(row.symbol()).isEqualTo("SYNN");
        assertThat(row.inputsSnapshot().path("position_id").asLong()).isEqualTo(43L);
        verify(telegram).notifyAlert(org.mockito.ArgumentMatchers.eq("SYNN"),
                org.mockito.ArgumentMatchers.eq("TARGET_HALF_UNCONFIRMED"),
                org.mockito.ArgumentMatchers.eq("CRITICAL"),
                org.mockito.ArgumentMatchers.contains("reset trim_count"));

        // Next pass: the book now carries trim_count 1 (qty still full) -> no second half-sale.
        ExecutorPosition reread = conviction(43L, "SYNN", 1, null, null, null);
        org.mockito.Mockito.clearInvocations(partialExit);
        service.apply(List.of(reread), Map.of("SYNN", new BigDecimal("133")), "run2");
        verify(partialExit, never()).execute(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** Final review #1: a REJECTED or TRIMMED target-half is not "unconfirmed" — the verdict is
     *  known and PartialExitService already booked it; nothing extra here. */
    @Test
    void targetHalfRejectedOrTrimmedIsNotMarkedUnconfirmed() {
        ExecutorPosition p = conviction(44L, "SYNO", 0, null, null, null);
        when(partialExit.execute(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PartialExitService.Result(PartialExitService.Outcome.REJECTED,
                        null, null, null, null));

        service.apply(List.of(p), Map.of("SYNO", new BigDecimal("131")), "run1");

        verify(positionRepo, never()).markTrimUnconfirmed(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(decisionRepo, never()).insert(any());
        verify(telegram, never()).notifyAlert(any(), any(), any(), any());
    }

    /** CONVICTION BUY like {@link #conviction}, with a recorded highest close. */
    private ExecutorPosition convictionWithHighest(long id, String symbol, int trimCount,
            BigDecimal highest) {
        ExecutorPosition base = ExecutorPositionFixtures.withoutKillLevel(id, "c", symbol, "BUY",
                BigDecimal.TEN, new BigDecimal("100"), new BigDecimal("65"), new BigDecimal("65"),
                1, null, List.of(), "sig-" + id, "strigoi-tech", "2026-07-01", null, "OPEN",
                "brk-" + id, highest, null, 0, null, null, null, null, "stop-" + id, null, null,
                null, null, trimCount, null, null, null, null, null, null, false,
                new BigDecimal("80"), "2026-07-01T09:00:00Z");
        return ExecutorPositionFixtures.conviction(base);
    }

    /** Final review #2: after the half-sale a >=30 % drop between two ratchets leaves active_stop
     *  on the old level (the ratchet skips a trail candidate the close is already below). The hard
     *  trigger compares the close to max(active_stop, trail) itself: highest 140 -> trail 98,
     *  close 95 -> HARD_STOP, measured names the trail. */
    @Test
    void convictionTrailBreachFiresEvenWhenActiveStopLagsBehind() {
        ExecutorPosition p = convictionWithHighest(45L, "SYNP", 1, new BigDecimal("140"));

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("SYNP", new BigDecimal("95")), "run1");

        assertThat(survivors).isEmpty();
        assertThat(gateway.flattenFractions).containsExactly(BigDecimal.ONE);
        DecisionLog row = onlyRow();
        assertThat(row.reasonCode()).isEqualTo("HARD_STOP");
        assertThat(row.vetoResults().get(0).path("measured").asString())
                .isEqualTo("STOP_BREACH: close 95 < trail 98 (highest 140 x (1 - 0.3); active stop 65)");
    }

    /** The trail check is CONVICTION-after-half-sale only: before the half-sale (trim_count 0) the
     *  same close above the emergency stop holds; and a close at/above the trail holds. */
    @Test
    void convictionTrailCheckOnlyAfterTheHalfSaleAndStrictlyBelow() {
        ExecutorPosition beforeHalf = convictionWithHighest(46L, "SYNQ", 0, new BigDecimal("140"));
        ExecutorPosition atTrail = convictionWithHighest(47L, "SYNR", 1, new BigDecimal("140"));

        List<ExecutorPosition> survivors = service.apply(List.of(beforeHalf, atTrail),
                Map.of("SYNQ", new BigDecimal("95"), "SYNR", new BigDecimal("98")), "run1");

        assertThat(survivors).containsExactly(beforeHalf, atTrail);
        assertThat(gateway.flattenedSymbols).isEmpty();
    }

    /** STANDARD rows never use the conviction trail, whatever their highest close. */
    @Test
    void standardIgnoresTheConvictionTrail() {
        ExecutorPosition base = ExecutorPositionFixtures.withoutKillLevel(48L, "c", "SYNS", "BUY",
                BigDecimal.TEN, new BigDecimal("100"), new BigDecimal("65"), new BigDecimal("65"),
                1, null, List.of(), "sig-48", "agent", "2026-07-01", null, "OPEN", "brk-48",
                new BigDecimal("140"), null, 0, null, null, null, null, "stop-48", null, null,
                null, null, 1, null, null, null, null, null, null, false, null, null);

        List<ExecutorPosition> survivors = service.apply(List.of(base),
                Map.of("SYNS", new BigDecimal("95")), "run1");

        assertThat(survivors).containsExactly(base);
    }

    // ---------------------------------------------------------------------------------------
    // Exit profile MOMENTUM (spec 2026-10-04 §3): wide stop only — no giveback, no kill level,
    // no target-half.
    // ---------------------------------------------------------------------------------------

    /** MOMENTUM BUY, entry 100, emergency stop 65 (R = 35/share), qty 10. */
    private ExecutorPosition momentum(long id, String symbol, BigDecimal mfeR) {
        return ExecutorPositionFixtures.withProfileFields(conviction(id, symbol, 0, null, null, mfeR),
                ExitProfile.MOMENTUM, null, null, null, false);
    }

    /** mfeR 3 / currentR 1 (close 135) is a GIVEBACK_BREACH for STANDARD; MOMENTUM holds. */
    @Test
    void momentumHasNoGiveback() {
        ExecutorPosition p = momentum(60L, "SYNM", new BigDecimal("3"));

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("SYNM", new BigDecimal("135")), "run1");

        assertThat(survivors).containsExactly(p);
        assertThat(gateway.flattenedSymbols).isEmpty();
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void momentumIgnoresAKillLevel() {
        ExecutorPosition p = ExecutorPositionFixtures.withKillLevel(momentum(61L, "SYNN", null),
                new BigDecimal("110"), null);

        service.apply(List.of(p), Map.of("SYNN", new BigDecimal("101")), "run1");

        assertThat(gateway.flattenedSymbols).isEmpty();
    }

    @Test
    void momentumHasNoTargetHalf() {
        ExecutorPosition p = momentum(62L, "SYNO", null);

        service.apply(List.of(p), Map.of("SYNO", new BigDecimal("140")), "run1");

        verify(partialExit, never()).execute(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void momentumStopBreachIsAHardStop() {
        ExecutorPosition p = momentum(63L, "SYNP", null);

        service.apply(List.of(p), Map.of("SYNP", new BigDecimal("64.99")), "run1");

        assertThat(onlyRow().reasonCode()).isEqualTo("HARD_STOP");
    }

    /** No trail exists for MOMENTUM even with trim_count > 0 (never reachable, pinned anyway). */
    @Test
    void momentumNeverChecksATrail() {
        ExecutorPosition trimmed = ExecutorPositionFixtures.withProfileFields(
                convictionWithHighest(64L, "SYNQ", 1, new BigDecimal("200")), ExitProfile.MOMENTUM,
                null, null, null, false);

        service.apply(List.of(trimmed), Map.of("SYNQ", new BigDecimal("139")), "run1");

        assertThat(gateway.flattenedSymbols).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // HARD_REBALANCE (spec 2026-10-04 §4)
    // ---------------------------------------------------------------------------------------

    private static final String FLAGGED = "2026-10-30 22:40:00+00";

    private ExecutorPosition flaggedMomentum(long id, String symbol) {
        return ExecutorPositionFixtures.withRebalanceExitAt(momentum(id, symbol, null), FLAGGED);
    }

    @Test
    void rebalanceFlagFlattensFully() {
        ExecutorPosition p = flaggedMomentum(70L, "SYNR");

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("SYNR", new BigDecimal("120")), "run1");

        assertThat(survivors).isEmpty();
        assertThat(gateway.flattenFractions).containsExactly(BigDecimal.ONE);
        verify(positionRepo).markPendingExit(org.mockito.ArgumentMatchers.eq(70L),
                org.mockito.ArgumentMatchers.eq("HARD_REBALANCE"), any(), any(),
                org.mockito.ArgumentMatchers.eq(NOW));
        DecisionLog row = onlyRow();
        assertThat(row.action()).isEqualTo("LOG_HARD_EXIT");
        assertThat(row.reasonCode()).isEqualTo("HARD_REBALANCE");
        assertThat(row.vetoResults().get(0).path("check").asString()).isEqualTo("REBALANCE");
        assertThat(row.vetoResults().get(0).path("measured").asString())
                .isEqualTo("REBALANCE: dropped out of the final momentum Top 10 (flagged " + FLAGGED + ")");
    }

    /** Like CATASTROPHE: evaluated before the null-close skip — close and current_r recorded null. */
    @Test
    void rebalanceFlagFlattensWithoutAClose() {
        ExecutorPosition p = flaggedMomentum(71L, "SYNS");

        List<ExecutorPosition> survivors = service.apply(List.of(p), Map.of(), "run1");

        assertThat(survivors).isEmpty();
        assertThat(gateway.flattenedSymbols).containsExactly("SYNS");
        DecisionLog row = onlyRow();
        assertThat(row.reasonCode()).isEqualTo("HARD_REBALANCE");
        assertThat(row.inputsSnapshot().path("close").isNull()).isTrue();
        assertThat(row.inputsSnapshot().path("current_r").isNull()).isTrue();
    }

    /** With a close, the stop wins the reason code. */
    @Test
    void stopBreachWinsOverTheRebalance() {
        ExecutorPosition p = flaggedMomentum(72L, "SYNT");

        service.apply(List.of(p), Map.of("SYNT", new BigDecimal("64.99")), "run1");

        assertThat(gateway.flattenFractions).containsExactly(BigDecimal.ONE);
        assertThat(onlyRow().reasonCode()).isEqualTo("HARD_STOP");
    }

    /** A row whose flatten is already pending is never flattened again — with or without a close. */
    @Test
    void pendingExitRowIsNotReflattened() {
        ExecutorPosition pending = ExecutorPositionFixtures.withPendingExit(
                flaggedMomentum(73L, "SYNU"), "HARD_REBALANCE");
        ExecutorPosition pendingNoClose = ExecutorPositionFixtures.withPendingExit(
                flaggedMomentum(74L, "SYNV"), "HARD_REBALANCE");

        List<ExecutorPosition> survivors = service.apply(List.of(pending, pendingNoClose),
                Map.of("SYNU", new BigDecimal("120")), "run1");

        assertThat(survivors).containsExactly(pending, pendingNoClose);
        assertThat(gateway.flattenedSymbols).isEmpty();
    }

    /** The flag only means something on a MOMENTUM row. */
    @Test
    void aFlagOnAStandardRowIsIgnored() {
        ExecutorPosition std = ExecutorPositionFixtures.withRebalanceExitAt(openPosition(75L, "SYNW",
                "BUY", new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), null), FLAGGED);

        service.apply(List.of(std), Map.of("SYNW", new BigDecimal("101")), "run1");

        assertThat(gateway.flattenedSymbols).isEmpty();
    }

    /** No broker verdict: escalated, the row survives with its flag — the next run retries. */
    @Test
    void rebalanceFlattenOutageEscalatesAndKeepsTheFlag() {
        ExecutorPosition p = flaggedMomentum(76L, "SYNX");
        gateway.unavailable = true;

        List<ExecutorPosition> survivors = service.apply(List.of(p),
                Map.of("SYNX", new BigDecimal("120")), "run1");

        assertThat(survivors).containsExactly(p);
        assertThat(onlyRow().reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        verify(positionRepo, never()).markPendingExit(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
    }

    @Test
    void infoLineCountsRebalanceExits() {
        var infos = linesWhile(HardTriggerService.class, ch.qos.logback.classic.Level.INFO,
                () -> service.apply(List.of(flaggedMomentum(77L, "SYNY"), flaggedMomentum(78L, "SYNZ")),
                        Map.of("SYNY", new BigDecimal("120")), "run1"));

        assertThat(infos).containsExactly("kill levels evaluated: 0 of 2 filled positions "
                + "(breached: 0); catastrophe flagged: 0, targets hit: 0, rebalance exits: 2");
    }

    /** Spec 2026-10-06 §5.2: current_r of a CONVICTION row uses entry × 0.35, not entry − initial_stop. */
    @Test
    void convictionCurrentRUsesTheAverageRisk() {
        ExecutorPosition base = ExecutorPositionFixtures.withoutKillLevel(41L, "c", "TECHB", "BUY",
                BigDecimal.TEN, new BigDecimal("60"), new BigDecimal("65"), new BigDecimal("39.00"),
                1, null, List.of(), "sig-41", "strigoi-tech", "2026-07-01", null, "OPEN", "brk-41",
                null, null, 0, null, null, null, null, "stop-41", null, null, null, null, 0, null,
                null, null, null, null, null, false, new BigDecimal("39.00"), "2026-07-01T09:00:00Z");
        ExecutorPosition p = ExecutorPositionFixtures.withProfileFields(base, ExitProfile.CONVICTION,
                "synthetic fraud finding", "2026-07-07 22:30:00+00", null, false);

        service.apply(List.of(p), Map.of("TECHB", new BigDecimal("50")), "run1");

        DecisionLog row = onlyRow();
        assertThat(row.inputsSnapshot().path("current_r").decimalValue()).isEqualByComparingTo("-0.476190");
    }

    /** Spec 2026-10-06 §6.1 row 2 (D8): the savings consolidator's emergency flatten reuses the hard-exit path. */
    @Test
    void hardExitSubmitsAFullFlattenAsHardStop() {
        ExecutorPosition p = conviction(50L, "TECHA", 0, null, null, null);

        HardTriggerService.HardExitOutcome out = service.hardExit(p, new BigDecimal("60"),
                "SAVINGS_EMERGENCY: synthetic", "run1");

        assertThat(out).isEqualTo(HardTriggerService.HardExitOutcome.SUBMITTED);
        assertThat(gateway.flattenFractions).containsExactly(BigDecimal.ONE);
        verify(positionRepo).markPendingExit(org.mockito.ArgumentMatchers.eq(50L),
                org.mockito.ArgumentMatchers.eq("HARD_STOP"), any(), any(), org.mockito.ArgumentMatchers.eq(NOW));
        DecisionLog row = onlyRow();
        assertThat(row.action()).isEqualTo("LOG_HARD_EXIT");
        assertThat(row.vetoResults().get(0).path("measured").asString()).startsWith("SAVINGS_EMERGENCY");
    }

    @Test
    void hardExitReportsAGonePositionAndAFailure() {
        ExecutorPosition p = conviction(51L, "TECHB", 0, null, null, null);
        gateway.rejectFlattenWith = new BrokerRejectedException("gone", "NO_POSITION", List.of());
        assertThat(service.hardExit(p, null, "SAVINGS_EMERGENCY: synthetic", "run1"))
                .isEqualTo(HardTriggerService.HardExitOutcome.POSITION_GONE);

        gateway.rejectFlattenWith = new BrokerRejectedException("no", "MARKET_CLOSED", List.of());
        assertThat(service.hardExit(p, null, "SAVINGS_EMERGENCY: synthetic", "run1"))
                .isEqualTo(HardTriggerService.HardExitOutcome.FAILED);

        gateway.rejectFlattenWith = new BrokerUnavailableException("down");
        assertThat(service.hardExit(p, null, "SAVINGS_EMERGENCY: synthetic", "run1"))
                .isEqualTo(HardTriggerService.HardExitOutcome.FAILED);
        verify(positionRepo, never()).markPendingExit(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
    }

    /** Spec 2026-10-06 §6.2 (R4 Minor 2): an UNPROTECTED savings position gets the stop breach and the
     *  catastrophe, never TARGET_HALF. */
    @Test
    void aStopOnlyPositionNeverSellsHalf() {
        ExecutorPosition p = conviction(60L, "TECHA", 0, null, null, null);

        service.apply(List.of(p), Map.of("TECHA", new BigDecimal("131")), "run1", java.util.Set.of(60L));

        verify(partialExit, never()).execute(any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(gateway.flattenedSymbols).isEmpty();
    }

    @Test
    void aStopOnlyPositionStillBreachesItsStop() {
        ExecutorPosition p = conviction(61L, "TECHA", 0, null, null, null);

        service.apply(List.of(p), Map.of("TECHA", new BigDecimal("60")), "run1", java.util.Set.of(61L));

        assertThat(gateway.flattenedSymbols).containsExactly("TECHA");
    }
}
