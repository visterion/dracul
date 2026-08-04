package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.FakeExecutionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the ratchet only ever raises (BUY) stops and never lowers one — the guard must
 *  deny every non-improving move, proving zero down-moves end to end. */
class StopRatchetServiceTest {

    private final FakeExecutionGateway gateway = new FakeExecutionGateway();
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
    private final RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorNotifier executorNotifier = mock(ExecutorNotifier.class);

    private RecordingStopRatchetService service;

    /** Captures the backoff seam so retry tests neither sleep nor guess at timing. */
    private static class RecordingStopRatchetService extends StopRatchetService {
        final List<Long> backoffs = new java.util.ArrayList<>();

        RecordingStopRatchetService(FakeExecutionGateway gateway, ExecutorPositionRepository positionRepo,
                DecisionLogRepository decisionRepo, RuleVersionProvider ruleVersions,
                StopRatchetGuard guard, ObjectMapper mapper, ExecutorNotifier notifier,
                double chandelierMult, int retryAttempts, long retryBackoffMs, long retryBudgetMs) {
            super(gateway, positionRepo, decisionRepo, ruleVersions, guard, mapper, notifier,
                    chandelierMult, retryAttempts, retryBackoffMs, retryBudgetMs);
        }

        @Override
        protected void backoff(long millis) {
            backoffs.add(millis);
        }
    }

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-v0.2");
        service = newService(3, 500L, 10_000L);
    }

    private RecordingStopRatchetService newService(int attempts, long backoffMs, long budgetMs) {
        return new RecordingStopRatchetService(gateway, positionRepo, decisionRepo, ruleVersions,
                new StopRatchetGuard(), mapper, executorNotifier, 3.0, attempts, backoffMs, budgetMs);
    }

    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal highestPrice,
            BigDecimal activeStop, BigDecimal mfeR, int softConfirmCount) {
        return openPosition(id, symbol, side, highestPrice, activeStop, mfeR, softConfirmCount,
                "brk-1", 1, null, null);
    }

    /**
     * Full fixture. {@code brokerOrderId} and {@code stopOrderId} are deliberately DIFFERENT
     * ("brk-1" vs "stop-1") — that difference is what makes the bracket-id assertions a real
     * mutation probe rather than a tautology.
     */
    private ExecutorPosition openPosition(long id, String symbol, String side, BigDecimal highestPrice,
            BigDecimal activeStop, BigDecimal mfeR, int softConfirmCount,
            String brokerOrderId, int tranche, String tranche2OrderId, String tranche2StopOrderId) {
        return new ExecutorPosition(id, "c", symbol, side, BigDecimal.TEN, new BigDecimal("100"),
                new BigDecimal("90"), activeStop, tranche, null, List.of(), "sig-1", "agent", "2026-07-01",
                null, "OPEN", brokerOrderId, highestPrice, mfeR, softConfirmCount, null, null, null, null,
                "stop-1", null, null, tranche2OrderId, tranche2StopOrderId, 0, null, null,
                null, null, null, null);
    }

    @Test
    void usesBracketIdNotStopLegId() {
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        FakeExecutionGateway.ModifyCall call = gateway.modifyCalls.get(0);
        // Agora's modifyBracket resolves legs FROM the bracket id; handing it a leg id fails in
        // both lifecycle phases. The fixture sets brokerOrderId="brk-1" and stopOrderId="stop-1"
        // deliberately different, so this assertion is a real probe.
        assertThat(call.orderId()).isEqualTo("brk-1");
        assertThat(call.symbol()).isEqualTo("ACME");
        assertThat(call.stop()).isEqualByComparingTo("104");
        assertThat(call.target()).isNull();

        ArgumentCaptor<BigDecimal> newStopCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("110")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("1.0")),
                org.mockito.ArgumentMatchers.eq(0),
                newStopCaptor.capture(), org.mockito.ArgumentMatchers.isNull());
        assertThat(newStopCaptor.getValue()).isEqualByComparingTo("104");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.triggerType()).isEqualTo("MAINTENANCE");
        assertThat(log.action()).isEqualTo("MODIFY_STOP");
        assertThat(log.reasonCode()).isNull();
        assertThat(log.symbol()).isEqualTo("ACME");
        assertThat(log.ruleVersion()).isEqualTo("exec-v0.2");
        assertThat(log.orderJson().get("new_stop").asDouble()).isEqualTo(104.0);
        assertThat(log.orderJson().get("stop_basis").asString()).contains("chandelier");

        verify(executorNotifier).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void neverLowersStop() {
        // chandelier = 110 - 3.0*5.33 ~= 94, below the existing active stop of 95 -> denied.
        ExecutorPosition p = openPosition(2L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("5.33")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any());
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void missingAtr_skips() {
        ExecutorPosition p = openPosition(3L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of(),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any());
        // A symbol missing from the ATR map is a routine condition, not a fault: never escalate.
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void brokerUnavailable_escalates() {
        ExecutorPosition p = openPosition(4L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);
        gateway.unavailable = true;

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(log.symbol()).isEqualTo("ACME");
        assertThat(log.orderJson()).isNotNull();
        assertThat(log.orderJson().get("position_id").asLong()).isEqualTo(4L);

        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any());
        // No "stop raised" push may go out when the stop did not move.
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void tranche2_nullLegId_stillEscalates() {
        // THE case that matters: on Saxo, tranche2StopOrderId is null BY DESIGN (the broker
        // returns no leg ids), so a gate keyed on that field would never fire. tranche is the
        // reliable marker.
        ExecutorPosition p = openPosition(5L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", null);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
        assertThat(log.symbol()).isEqualTo("ACME");
        assertThat(log.orderJson()).isNotNull();
        assertThat(log.orderJson().get("position_id").asLong()).isEqualTo(5L);
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void tranche2_withLegId_escalates() {
        ExecutorPosition p = openPosition(6L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", "s2");

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
        assertThat(logCaptor.getValue().orderJson()).isNotNull();
        assertThat(logCaptor.getValue().orderJson().get("position_id").asLong()).isEqualTo(6L);
    }

    @Test
    void tranche2OrderIdAlone_escalates() {
        // Belt-and-braces disjunct: tranche still 1, but a tranche-2 entry id is present.
        ExecutorPosition p = openPosition(7L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 1, "t2-1", null);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
        assertThat(logCaptor.getValue().orderJson()).isNotNull();
        assertThat(logCaptor.getValue().orderJson().get("position_id").asLong()).isEqualTo(7L);
    }

    @Test
    void tranche2StopOrderIdAlone_escalates() {
        // The third disjunct, for brokers that DO report leg ids: tranche still 1, no tranche-2
        // entry id, but a second stop leg is on record. Without this test an implementation that
        // drops `|| p.tranche2StopOrderId() != null` passes the whole suite.
        ExecutorPosition p = openPosition(22L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 1, null, "s2");

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
        assertThat(logCaptor.getValue().orderJson().get("position_id").asLong()).isEqualTo(22L);
    }

    @Test
    void guardDenied_tranche2_writesNothing() {
        // Proves the gate sits AFTER guard.permit: chandelier 110 - 3*5.33 = 94.01 < stop 95,
        // so the guard denies first and no escalation row is written at all.
        ExecutorPosition p = openPosition(8L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", null);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("5.33")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void nullBrokerOrderId_escalatesNoBracketId() {
        ExecutorPosition p = openPosition(9L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, null, 1, null, null);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("NO_BRACKET_ID");
        assertThat(log.orderJson()).isNotNull();
        assertThat(log.orderJson().get("position_id").asLong()).isEqualTo(9L);
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any());
        // No "stop raised" push may go out when the stop did not move.
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void chandelierRounded_buyDown() {
        // 110 - 3.0*2.001 = 103.997 -> FLOOR at 2dp -> 103.99 (never above the computed level).
        ExecutorPosition p = openPosition(10L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.001")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).stop()).isEqualByComparingTo("103.99");

        ArgumentCaptor<BigDecimal> newStopCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(10L),
                any(), any(), any(Integer.class), newStopCaptor.capture(), any());
        // Sent == persisted. The book must never claim a stop the broker didn't get.
        assertThat(newStopCaptor.getValue()).isEqualByComparingTo("103.99");
    }

    @Test
    void chandelierRounded_sellUp() {
        // SELL: 90 + 3.0*2.001 = 96.003 -> CEILING at 2dp -> 96.01. Guard permits because the
        // proposed stop (96.01) is below the active stop (100) for a short.
        ExecutorPosition p = openPosition(11L, "ACME", "SELL", new BigDecimal("90"),
                new BigDecimal("100"), new BigDecimal("1.0"), 0);

        // SELL needs a price BELOW the chandelier of 96.01 for the safe-side check to pass.
        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.001")),
                Map.of("ACME", new BigDecimal("90")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).orderId()).isEqualTo("brk-1");
        assertThat(gateway.modifyCalls.get(0).stop()).isEqualByComparingTo("96.01");
    }

    @Test
    void subCentImprovement_isDeniedAfterRounding() {
        // Unrounded 110.004 - 6 = 104.004 would beat the active stop of 104.00 and trigger a
        // pointless modify + Telegram push every single run. Rounded first, it is 104.00 and the
        // guard denies it. This pins that rounding happens BEFORE guard.permit.
        ExecutorPosition p = openPosition(12L, "ACME", "BUY", new BigDecimal("110.004"),
                new BigDecimal("104.00"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(decisionRepo, never()).insert(any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void chandelierAboveMarket_isSkippedSilently() {
        // Price fell more than 3xATR off the high but is still above the hard stop: the chandelier
        // (104) now sits ABOVE the market (100). Sending a sell-stop above the market would either
        // be rejected by the broker — the very error flood this slice ends — or fill immediately,
        // bypassing the soft-confirm design. Skip, and stay silent: this is a regular "not yet"
        // state handled by the soft trigger, not a fault.
        ExecutorPosition p = openPosition(13L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("100")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(decisionRepo, never()).insert(any());
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any());
    }

    @Test
    void tranche2_wrongSideChandelier_skipsSilently() {
        // Pins the order of the two gates: the market-side skip runs BEFORE the tranche-2
        // escalation, so a position that is both unsendable AND tranche-2 produces no row at all.
        // It gets its escalation on the next run where the price is back above the chandelier.
        ExecutorPosition p = openPosition(14L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", null);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("100")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void missingPrice_skips() {
        // Defensive only: ExecutorIndicators sets available=true only when ATR AND close are
        // present, so via MaintenancePipeline every symbol in atrBySymbol is also in closeBySymbol.
        ExecutorPosition p = openPosition(15L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")), Map.of(), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void sellChandelierBelowMarket_isSkippedSilently() {
        // Mirror of chandelierAboveMarket_isSkippedSilently for a short. Same fixture as
        // chandelierRounded_sellUp — chandelier = 90 + 3.0*2.001 = 96.003 -> CEILING -> 96.01,
        // which the guard PERMITS because it sits below the active stop of 100 for a short. So only
        // the market-side check can bite here: at a price of 100 that buy-stop would sit BELOW the
        // market, filling immediately and closing the short, bypassing the soft-confirm design.
        //
        // This test is the only thing standing between the code and a degenerate SELL branch such
        // as `"SELL".equals(side) || chandelier < price`, which every other test still passes.
        ExecutorPosition p = openPosition(16L, "ACME", "SELL", new BigDecimal("90"),
                new BigDecimal("100"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.001")),
                Map.of("ACME", new BigDecimal("100")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(decisionRepo, never()).insert(any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void chandelierEqualToMarket_isSkipped() {
        // Both comparisons are strict, so equality skips. A stop exactly at the last close is not
        // the safe side — it is a coin flip on the next tick.
        ExecutorPosition p = openPosition(17L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("104")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void highestPriceNull_skips() {
        // A position that never got a highest price recorded (no maintenance run yet) has no
        // chandelier basis at all: skip silently, never escalate and never send a null level.
        ExecutorPosition p = openPosition(18L, "ACME", "BUY", null,
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void escalatingPositionDoesNotAbortOthers() {
        // Every skip/escalation path uses `continue`, never a return: one bad position must not
        // cost the whole book its ratchet.
        ExecutorPosition tranche2 = openPosition(19L, "AAA", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", null);
        ExecutorPosition noBracket = openPosition(20L, "BBB", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, null, 1, null, null);
        ExecutorPosition healthy = openPosition(21L, "CCC", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(tranche2, noBracket, healthy),
                Map.of("AAA", new BigDecimal("2.0"), "BBB", new BigDecimal("2.0"),
                        "CCC", new BigDecimal("2.0")),
                Map.of("AAA", new BigDecimal("110"), "BBB", new BigDecimal("110"),
                        "CCC", new BigDecimal("110")),
                "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).symbol()).isEqualTo("CCC");
        assertThat(gateway.modifyCalls.get(0).orderId()).isEqualTo("brk-1");
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(21L),
                any(), any(), any(Integer.class), any(), any());
    }

    // -------------------------------------------------------------------
    // D10 — transient broker failures are retried inside the same run
    // -------------------------------------------------------------------

    /** Verbatim shape of the message Agora surfaced on the 429 that triggered this fix. */
    private static final String RATE_LIMITED = "saxo rate limited (HTTP 429) — retry shortly";

    @Test
    void transientRateLimit_isRetriedAndSucceeds() {
        // A rate limit is a "come back in a moment", not a defect: the identical PATCH succeeded
        // on the next run 12 minutes later. Escalating immediately fired a HIGH alarm and left
        // the ratchet unapplied for those 12 minutes.
        ExecutorPosition p = openPosition(30L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);
        gateway.modifyFailures = 1;
        gateway.modifyFailureMessage = RATE_LIMITED;

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);
        assertThat(service.backoffs).containsExactly(500L);
        // No escalation, and the book gets the new stop the broker confirmed on attempt 2.
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("MODIFY_STOP");
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(30L),
                any(), any(), any(Integer.class), any(), any());
        verify(executorNotifier).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void transientRateLimit_exhaustsAttemptsThenEscalates() {
        ExecutorPosition p = openPosition(31L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);
        gateway.modifyFailures = 99;
        gateway.modifyFailureMessage = RATE_LIMITED;

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(3);
        assertThat(service.backoffs).containsExactly(500L, 1000L);   // exponential

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(log.reasoning()).contains("3 attempt");
        assertThat(log.orderJson().get("position_id").asLong()).isEqualTo(31L);

        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    /**
     * D10 missed a whole class of REAL 429. {@code AgoraExecutionGateway.call} wraps every
     * transport failure into the constant {@code "agora trading call failed: " + tool}, throwing
     * the status away — while {@code HttpClientErrorException$TooManyRequests: 429 Too Many
     * Requests} is live in this stack. The classifier only ever saw the message, never the cause
     * chain, so the genuine rate limit escalated on the first attempt as though it were a
     * structural defect.
     */
    @Test
    void aRateLimitCarriedOnlyByTheCauseChain_isStillTransient() {
        ExecutorPosition p = openPosition(35L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);
        gateway.modifyFailures = 1;
        gateway.modifyFailureMessage = "agora trading call failed: modify_bracket";
        gateway.modifyFailureCause = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);
        assertThat(service.backoffs).containsExactly(500L);
    }

    /**
     * The bare {@code contains("429")} it replaced could fire on any message that merely happens
     * to contain those three digits — an Alpaca reject echoing a price near $429, a 10-digit
     * order id, a quantity. Retrying a structural rejection wastes the stop-protection budget and
     * delays the escalation; every real rate limit already says {@code HTTP 429} or names itself.
     */
    @Test
    void aRejectionThatMerelyContainsTheDigits429_isNotTransient() {
        for (String message : List.of(
                "agora order rejected [PRICE_OUT_OF_RANGE]: limit 429.50 outside collar",
                "agora order rejected [DUPLICATE]: order id 1004296318 already exists",
                "agora order rejected [QTY]: 4290 exceeds position size")) {
            gateway.modifyCalls.clear();
            service.backoffs.clear();
            gateway.modifyFailures = 99;
            gateway.modifyFailureCause = null;
            gateway.modifyFailureMessage = message;
            ExecutorPosition p = openPosition(36L, "ACME", "BUY", new BigDecimal("110"),
                    new BigDecimal("95"), new BigDecimal("1.0"), 0);

            service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                    Map.of("ACME", new BigDecimal("110")), "run1");

            assertThat(gateway.modifyCalls).as("message: %s", message).hasSize(1);
            assertThat(service.backoffs).as("message: %s", message).isEmpty();
        }
    }

    /** The real Agora wording must keep working — this is the shape that triggered D10. */
    @Test
    void theObservedAgoraRateLimitWording_isStillTransient() {
        for (String message : List.of(
                "saxo rate limited (HTTP 429) — retry shortly",
                "alpaca rate limited (http 429)",
                "429 Too Many Requests",
                "provider returned status 429")) {
            gateway.modifyCalls.clear();
            service.backoffs.clear();
            gateway.modifyFailures = 1;
            gateway.modifyFailureCause = null;
            gateway.modifyFailureMessage = message;
            ExecutorPosition p = openPosition(37L, "ACME", "BUY", new BigDecimal("110"),
                    new BigDecimal("95"), new BigDecimal("1.0"), 0);

            service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                    Map.of("ACME", new BigDecimal("110")), "run1");

            assertThat(gateway.modifyCalls).as("message: %s", message).hasSize(2);
        }
    }

    @Test
    void nonTransientRejection_escalatesOnTheFirstAttempt() {
        // LEG_NOT_FOUND (seen 2026-07-26) is a structural rejection — retrying is pointless noise.
        ExecutorPosition p = openPosition(32L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);
        gateway.modifyFailures = 99;
        gateway.modifyFailureMessage = "agora order rejected [LEG_NOT_FOUND]: no stop-loss leg";

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(service.backoffs).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(logCaptor.getValue().reasoning()).contains("LEG_NOT_FOUND");
    }

    @Test
    void retryBudget_isSharedAcrossTheWholeRatchetPass() {
        // The whole ratchet runs inside the 30s fetch_open_positions tool call. The budget is a
        // wall-clock ceiling over the ENTIRE pass, so a book full of rate-limited positions can
        // never multiply the retries into a tool timeout. Budget 0 => not one retry anywhere.
        service = newService(3, 500L, 0L);
        ExecutorPosition a = openPosition(33L, "AAA", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);
        ExecutorPosition b = openPosition(34L, "BBB", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);
        gateway.modifyFailures = 99;
        gateway.modifyFailureMessage = RATE_LIMITED;

        service.ratchet(List.of(a, b),
                Map.of("AAA", new BigDecimal("2.0"), "BBB", new BigDecimal("2.0")),
                Map.of("AAA", new BigDecimal("110"), "BBB", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);   // one attempt each, no retries
        assertThat(service.backoffs).isEmpty();
        verify(decisionRepo, times(2)).insert(any());
    }

    @Test
    void singleAttemptConfigured_neverRetries() {
        service = newService(1, 500L, 10_000L);
        ExecutorPosition p = openPosition(35L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);
        gateway.modifyFailures = 99;
        gateway.modifyFailureMessage = RATE_LIMITED;

        service.ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(service.backoffs).isEmpty();
    }
}
