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
    private final ExecutorPositionLegRepository legRepo = mock(ExecutorPositionLegRepository.class);
    private final DecisionLogRepository decisionRepo = mock(DecisionLogRepository.class);
    private final RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorNotifier executorNotifier = mock(ExecutorNotifier.class);

    private RecordingStopRatchetService service;

    /** Captures the backoff seam so retry tests neither sleep nor guess at timing. */
    private static class RecordingStopRatchetService extends StopRatchetService {
        final List<Long> backoffs = new java.util.ArrayList<>();

        RecordingStopRatchetService(FakeExecutionGateway gateway, ExecutorPositionRepository positionRepo,
                ExecutorPositionLegRepository legRepo,
                DecisionLogRepository decisionRepo, RuleVersionProvider ruleVersions,
                StopRatchetGuard guard, ObjectMapper mapper, ExecutorNotifier notifier,
                double chandelierMult, int retryAttempts, long retryBackoffMs, long retryBudgetMs,
                BigDecimal bufferAtr) {
            super(gateway, positionRepo, legRepo, decisionRepo, ruleVersions, guard, mapper, notifier,
                    chandelierMult, retryAttempts, retryBackoffMs, retryBudgetMs, bufferAtr);
        }

        @Override
        protected void backoff(long millis) {
            backoffs.add(millis);
        }
    }

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-v0.2");
        // bufferAtr = 0 is the exact identity in BrokerStop, so every pre-SP1 expectation in this
        // file keeps describing the same price. The buffered tests build their own service.
        service = newService(3, 500L, 10_000L, BigDecimal.ZERO);
    }

    private RecordingStopRatchetService newService(int attempts, long backoffMs, long budgetMs) {
        return newService(attempts, backoffMs, budgetMs, BigDecimal.ZERO);
    }

    private RecordingStopRatchetService newService(int attempts, long backoffMs, long budgetMs,
            BigDecimal bufferAtr) {
        return new RecordingStopRatchetService(gateway, positionRepo, legRepo, decisionRepo, ruleVersions,
                new StopRatchetGuard(), mapper, executorNotifier, 3.0, attempts, backoffMs, budgetMs,
                bufferAtr);
    }

    /** Legacy-shaped call: the same ATR is the long, the short and the effective one, so every
     *  pre-SP1 test keeps exercising exactly the chandelier it was written for. */
    private void ratchet(List<ExecutorPosition> positions, Map<String, BigDecimal> atrBySymbol,
            Map<String, BigDecimal> closeBySymbol, String runId) {
        service.ratchet(positions, atrBySymbol, atrBySymbol, atrBySymbol, closeBySymbol, runId);
    }

    /** An OPEN leg row exactly as {@code executor_position_leg} holds it. */
    private ExecutorPositionLeg leg(long id, long positionId, int tranche, String entryOrderId,
            String stopOrderId, BigDecimal qty) {
        return new ExecutorPositionLeg(id, positionId, tranche, entryOrderId, stopOrderId, qty,
                ExecutorPositionLeg.OPEN, null, null, null);
    }

    /** Stubs the leg book for a position. Every test that does NOT call this exercises the legacy
     *  column path: the mock returns an empty leg list, which is what a position predating the
     *  leg table looks like. */
    private void withOpenLegs(long positionId, ExecutorPositionLeg... legs) {
        when(legRepo.findOpenByPosition(positionId)).thenReturn(List.of(legs));
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
                null, null, null, null, false, null, null);
    }

    @Test
    void usesBracketIdNotStopLegId() {
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
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
                newStopCaptor.capture(), org.mockito.ArgumentMatchers.isNull(), any());
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

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("5.33")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void missingAtr_skips() {
        ExecutorPosition p = openPosition(3L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        ratchet(List.of(p), Map.of(),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
        // A symbol missing from the ATR map is a routine condition, not a fault: never escalate.
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void brokerUnavailable_escalates() {
        ExecutorPosition p = openPosition(4L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);
        gateway.unavailable = true;

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(log.symbol()).isEqualTo("ACME");
        assertThat(log.orderJson()).isNotNull();
        assertThat(log.orderJson().get("position_id").asLong()).isEqualTo(4L);

        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
        // No "stop raised" push may go out when the stop did not move.
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void tranche2_missingSecondStopLegId_stillEscalates() {
        // The one case that genuinely cannot be handled: the position is in two tranches but the
        // book has no id for the second stop leg, so there is no way to name it. Guessing is what
        // the whole leg-addressing contract exists to avoid — escalate and leave the stop alone.
        ExecutorPosition p = openPosition(5L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", null);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
        // The COLUMN path, not the leg path -- the two are different conditions that happen to
        // share a reason code, and they die at different times.
        assertThat(log.inputsSnapshot().path("path").asString()).isEqualTo("COLUMN");
        assertThat(log.reasoning()).contains("tranche2_stop_order_id");
        assertThat(log.symbol()).isEqualTo("ACME");
        assertThat(log.orderJson()).isNotNull();
        assertThat(log.orderJson().get("position_id").asLong()).isEqualTo(5L);
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void tranche2_missingFirstStopLegId_stillEscalates() {
        ExecutorPosition p = new ExecutorPosition(23L, "c", "ACME", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("95"), 2, null, List.of(),
                "sig-1", "agent", "2026-07-01", null, "OPEN", "brk-1", new BigDecimal("110"),
                new BigDecimal("1.0"), 0, null, null, null, null,
                null /* stop_order_id missing */, null, null, "t2-1", "s2", 0, null, null,
                null, null, null, null, false, null, null);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
        assertThat(logCaptor.getValue().reasoning()).contains("stop_order_id");
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void tranche2_bothLegIds_ratchetsBothLegsToTheSameLevel() {
        // A protective stop is a price level on the UNDERLYING, not a per-tranche quantity: the
        // chandelier is derived from highestPrice and ATR, neither of which knows about entry
        // prices. Both legs therefore move to the identical level, and active_stop — one column
        // that stopguard reads for the whole position — is true of every share behind it.
        ExecutorPosition p = openPosition(6L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", "s2");

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);
        FakeExecutionGateway.ModifyCall first = gateway.modifyCalls.get(0);
        assertThat(first.orderId()).isEqualTo("brk-1");
        assertThat(first.stopOrderId()).isEqualTo("stop-1");
        assertThat(first.stop()).isEqualByComparingTo("104");
        assertThat(first.target()).isNull();
        assertThat(first.targetOrderId()).isNull();

        FakeExecutionGateway.ModifyCall second = gateway.modifyCalls.get(1);
        assertThat(second.orderId()).isEqualTo("t2-1");
        assertThat(second.stopOrderId()).isEqualTo("s2");
        assertThat(second.stop()).isEqualByComparingTo("104");

        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(6L),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("110")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("1.0")),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.argThat(v -> v.compareTo(new BigDecimal("104")) == 0),
                org.mockito.ArgumentMatchers.isNull(), any());
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("MODIFY_STOP");
        verify(executorNotifier).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void tranche2_secondLegFails_keepsTheOldStopAndReportsPartial() {
        // Broker first, book second, and half a broker is not a book entry: leg 1 is already at
        // 104 but leg 2 is still at 95, so active_stop must stay 95 — stopguard trusts it for the
        // WHOLE position, and claiming 104 would be claiming protection 22 of 46 shares lack.
        ExecutorPosition p = openPosition(9L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", "s2");
        gateway.modifyFailures = 1;
        gateway.failModifyForStopOrderId = "s2";
        gateway.modifyFailureMessage = "boom";

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);
        // The partial path writes broker_stop -- the leg that moved really rests there -- but
        // active_stop must stay at the level EVERY share still honours.
        verify(positionRepo).updateMaintenance(anyLong(), any(), any(), any(Integer.class),
                org.mockito.ArgumentMatchers.argThat(
                        (BigDecimal v) -> v != null && v.compareTo(new BigDecimal("95")) == 0),
                org.mockito.ArgumentMatchers.isNull(), any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, times(2)).insert(logCaptor.capture());
        List<DecisionLog> logs = logCaptor.getAllValues();
        assertThat(logs.get(0).reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(logs.get(1).action()).isEqualTo("ESCALATE");
        assertThat(logs.get(1).reasonCode()).isEqualTo("PARTIAL_TRANCHE_RATCHET");
        // A partial must be readable as a partial: which leg moved, which did not, and to what.
        assertThat(logs.get(1).reasoning()).contains("stop-1").contains("s2").contains("104");
        assertThat(logs.get(1).orderJson().get("position_id").asLong()).isEqualTo(9L);
        // One reason code, one JSON shape: the legacy path writes the same one-element array the
        // leg path writes.
        assertThat(logs.get(1).orderJson().get("moved_stop_order_ids").get(0).asString())
                .isEqualTo("stop-1");
        assertThat(logs.get(1).orderJson().get("unmoved_stop_order_id").asString()).isEqualTo("s2");
    }

    @Test
    void tranche2_firstLegFails_touchesNothingElse() {
        // Nothing moved at the broker, so this is an ordinary failed ratchet — one escalation, no
        // second modify call, and emphatically not a "partial".
        ExecutorPosition p = openPosition(10L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", "s2");
        gateway.modifyFailures = 99;
        gateway.failModifyForStopOrderId = "stop-1";
        service = newService(1, 0L, 0L);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void tranche2OrderIdAlone_escalates() {
        // Belt-and-braces disjunct: tranche still 1, but a tranche-2 entry id is present and no
        // second stop leg id — unaddressable, so still an escalation.
        ExecutorPosition p = openPosition(7L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 1, "t2-1", null);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
        assertThat(logCaptor.getValue().orderJson()).isNotNull();
        assertThat(logCaptor.getValue().orderJson().get("position_id").asLong()).isEqualTo(7L);
    }

    @Test
    void tranche2StopOrderIdAlone_ratchetsBothLegs() {
        // The third disjunct, for a broker that DOES report leg ids (Saxo does — verified on the
        // paper book 2026-08-04): tranche still 1 on the book, no tranche-2 entry id, but a second
        // stop leg is on record. Both ids exist, so both legs are addressable and both move. The
        // bracket id for the second leg falls back to broker_order_id — with an explicit leg id it
        // is context, not an address.
        ExecutorPosition p = openPosition(22L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 1, null, "s2");

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);
        assertThat(gateway.modifyCalls.get(0).stopOrderId()).isEqualTo("stop-1");
        assertThat(gateway.modifyCalls.get(1).stopOrderId()).isEqualTo("s2");
        assertThat(gateway.modifyCalls.get(1).orderId()).isEqualTo("brk-1");
        verify(positionRepo).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
    }

    /** Synthetic collapsed-position fixture: {@code stop_legs_collapsed} true, with whatever the
     *  test wants in the two stop-leg id columns. Order ids are invented 10-digit values. */
    private ExecutorPosition collapsedPosition(long id, String symbol, String stopOrderId,
            String tranche2StopOrderId) {
        return new ExecutorPosition(id, "c", symbol, "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("95"), 2, null, List.of(),
                "sig-1", "agent", "2026-07-01", null, "OPEN", "2000000000", new BigDecimal("110"),
                new BigDecimal("1.0"), 0, null, null, null, null,
                stopOrderId, null, null, "2000000003", tranche2StopOrderId, 0, null, null,
                null, null, null, null, true, null, null);
    }

    @Test
    void aCollapsedPositionStillNamingTwoLegsMovesBothByTheirOwnIds() {
        // BUG-S13. A collapse can legitimately leave BOTH id columns populated: recordTrim's
        // collapse branch writes a column only when a RESTORED (live) leg's `replaces` names it,
        // and Agora's allocator can hand back more than one live leg. Gating the two-leg path on
        // stop_legs_collapsed made this position send ONE unnamed modify, which Agora resolves
        // through modifyBySymbolFallback — and that keeps only the LAST Stop order on the Uic. One
        // leg moved, the other silently kept its old price, picked by the broker's scan order.
        // Two named legs are two live legs, so both must move, each addressed by its own id.
        ExecutorPosition p = collapsedPosition(50L, "SYNA", "2000000001", "2000000002");

        ratchet(List.of(p), Map.of("SYNA", new BigDecimal("2.0")),
                Map.of("SYNA", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);
        assertThat(gateway.modifyCalls.get(0).stopOrderId()).isEqualTo("2000000001");
        assertThat(gateway.modifyCalls.get(0).stop()).isEqualByComparingTo("104");
        assertThat(gateway.modifyCalls.get(1).stopOrderId()).isEqualTo("2000000002");
        assertThat(gateway.modifyCalls.get(1).stop()).isEqualByComparingTo("104");
        // Neither call may leave the leg to the broker's fallback — that is the whole finding.
        assertThat(gateway.modifyCalls).allSatisfy(c -> assertThat(c.stopOrderId()).isNotNull());

        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(50L),
                any(), any(), any(Integer.class),
                org.mockito.ArgumentMatchers.argThat(v -> v.compareTo(new BigDecimal("104")) == 0),
                org.mockito.ArgumentMatchers.isNull(), any());
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("MODIFY_STOP");
        verify(executorNotifier).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void aCollapsedTwoLegPositionWhoseSecondLegFailsKeepsTheOldStop() {
        // Same shape, second leg refused by the broker. A half-moved exit is worse than one that
        // did not move: the book must NOT record 104, because only 104-for-everything would make
        // stopguard's single active_stop column true. It stays at 95, the level every share still
        // honours, and the run escalates PARTIAL_TRANCHE_RATCHET naming both legs.
        ExecutorPosition p = collapsedPosition(51L, "SYNB", "2000000001", "2000000002");
        gateway.modifyFailures = 1;
        gateway.failModifyForStopOrderId = "2000000002";
        gateway.modifyFailureMessage = "agora order rejected [LEG_NOT_FOUND]: no working order";
        gateway.modifyRejectCode = "LEG_NOT_FOUND";
        service = newService(1, 0L, 0L);

        ratchet(List.of(p), Map.of("SYNB", new BigDecimal("2.0")),
                Map.of("SYNB", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);
        // Book untouched: no new active_stop, and emphatically no success push.
        // The partial path writes broker_stop -- the leg that moved really rests there -- but
        // active_stop must stay at the level EVERY share still honours.
        verify(positionRepo).updateMaintenance(anyLong(), any(), any(), any(Integer.class),
                org.mockito.ArgumentMatchers.argThat(
                        (BigDecimal v) -> v != null && v.compareTo(new BigDecimal("95")) == 0),
                org.mockito.ArgumentMatchers.isNull(), any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, times(2)).insert(logCaptor.capture());
        List<DecisionLog> logs = logCaptor.getAllValues();
        // A stale book id fails LOUDLY once the leg is addressed by name — LEG_NOT_FOUND is a
        // structural rejection, escalated on the first attempt, never retried, and reported as
        // what it is: the leg is missing, the broker is not down.
        assertThat(logs.get(0).reasonCode()).isEqualTo("STOP_LEG_MISSING");
        assertThat(logs.get(0).reasoning()).contains("LEG_NOT_FOUND").contains("2000000002");
        assertThat(logs.get(1).action()).isEqualTo("ESCALATE");
        assertThat(logs.get(1).reasonCode()).isEqualTo("PARTIAL_TRANCHE_RATCHET");
        assertThat(logs.get(1).orderJson().get("moved_stop_order_ids").get(0).asString())
                .isEqualTo("2000000001");
        assertThat(logs.get(1).orderJson().get("unmoved_stop_order_id").asString()).isEqualTo("2000000002");
        assertThat(logs.get(1).orderJson().get("active_stop").asDouble()).isEqualTo(95.0);
        assertThat(logs.get(1).orderJson().get("attempted_stop").asDouble()).isEqualTo(104.0);
    }

    @Test
    void aCollapsedPositionDownToOneLegStillSendsExactlyOneUnnamedModify() {
        // The other side of the same routing decision: collapsed AND only one stop id left, so one
        // leg genuinely exists. Exactly one modify, still unnamed — with a single stop on the
        // instrument, "the last stop order" and "the only stop order" are the same order, so the
        // broker-side resolution is safe here in a way it is not for two legs. Unchanged by the fix.
        ExecutorPosition p = collapsedPosition(52L, "SYNA", "2000000001", null);

        ratchet(List.of(p), Map.of("SYNA", new BigDecimal("2.0")),
                Map.of("SYNA", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).orderId()).isEqualTo("2000000000");
        assertThat(gateway.modifyCalls.get(0).stopOrderId()).isNull();
        assertThat(gateway.modifyCalls.get(0).stop()).isEqualByComparingTo("104");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("MODIFY_STOP");
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(52L),
                any(), any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void aCollapsedPositionWithNoStopIdAtAllStillRatchetsThroughTheBracket() {
        // Degenerate but reachable: a collapse whose survivor matched neither column nulls both.
        // The collapse flag still explains the absence, so this must not escalate as a book bug —
        // one bracket-addressed modify, exactly as for any other single-legged position.
        ExecutorPosition p = collapsedPosition(53L, "SYNB", null, null);

        ratchet(List.of(p), Map.of("SYNB", new BigDecimal("2.0")),
                Map.of("SYNB", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).stopOrderId()).isNull();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("MODIFY_STOP");
    }

    @Test
    void aCollapsedTwoTranchePositionRatchetsItsSingleLeg() {
        // A trim folded the two stop legs into one because the remainder was too small to give
        // each leg at least one share: tranche 2, tranche2_order_id still set, but
        // tranche2_stop_order_id is gone and stop_legs_collapsed is true. Escalating forever here
        // (as TRANCHE_RATCHET_UNSUPPORTED would) is the bug this task fixes — the surviving single
        // leg must still ratchet through the ordinary single-tranche path.
        //
        // stop_order_id="stop-1" here is deliberately NOT the address used: see the stopOrderId()
        // assertion below for why naming it explicitly would be actively wrong, not merely
        // unverified.
        ExecutorPosition p = new ExecutorPosition(40L, "c", "ACME", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("95"), 2, null, List.of(),
                "sig-1", "agent", "2026-07-01", null, "OPEN", "brk-1", new BigDecimal("110"),
                new BigDecimal("1.0"), 0, null, null, null, null,
                "stop-1", null, null, "t2-1", null, 0, null, null,
                null, null, null, null, true, null, null);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        FakeExecutionGateway.ModifyCall call = gateway.modifyCalls.get(0);
        assertThat(call.orderId()).isEqualTo("brk-1");
        assertThat(call.stop()).isEqualByComparingTo("104");
        // Pins the addressing choice: null, letting the gateway resolve the surviving leg, NOT
        // p.stopOrderId() by name. This is not merely brief-literal but the CORRECT choice —
        // ExecutorPositionRepository.recordTrim's collapse branch can leave stop_order_id holding a
        // cancelled id if the survivor's `replaces` named the old tranche2 column (see the fix and
        // regression test on recordTrim). Naming that stale id here would send a modify against a
        // leg the broker no longer has; letting Agora resolve by bracket id / by-symbol fallback is
        // the only address that is guaranteed live for a collapsed position's single leg.
        assertThat(call.stopOrderId()).isNull();

        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(40L),
                any(), any(), any(Integer.class), any(), any(), any());
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("MODIFY_STOP");
        verify(executorNotifier).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void anUncollapsedTwoTranchePositionWithAMissingLegStillEscalates() {
        // Same row as above, but stop_legs_collapsed is false: this is the genuinely unaddressable
        // case the escalation exists for, and it must still fire exactly as before.
        ExecutorPosition p = new ExecutorPosition(41L, "c", "ACME", "BUY", BigDecimal.TEN,
                new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("95"), 2, null, List.of(),
                "sig-1", "agent", "2026-07-01", null, "OPEN", "brk-1", new BigDecimal("110"),
                new BigDecimal("1.0"), 0, null, null, null, null,
                "stop-1", null, null, "t2-1", null, 0, null, null,
                null, null, null, null, false, null, null);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
        // This row has no leg rows at all, so it is the legacy COLUMN path.
        assertThat(log.inputsSnapshot().path("path").asString()).isEqualTo("COLUMN");
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void anIntactTwoLegPositionStillRatchetsBothLegs() {
        // Regression: both leg ids present and not collapsed -> both legs still move, at the same
        // price, exactly as tranche2_bothLegIds_ratchetsBothLegsToTheSameLevel pins above.
        ExecutorPosition p = openPosition(42L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", "s2");

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);
        assertThat(gateway.modifyCalls.get(0).stop()).isEqualByComparingTo("104");
        assertThat(gateway.modifyCalls.get(1).stop()).isEqualByComparingTo("104");
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(42L),
                any(), any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void guardDenied_tranche2_writesNothing() {
        // Proves the gate sits AFTER guard.permit: chandelier 110 - 3*5.33 = 94.01 < stop 95,
        // so the guard denies first and no escalation row is written at all.
        ExecutorPosition p = openPosition(8L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", null);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("5.33")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(decisionRepo, never()).insert(any());
    }

    @Test
    void nullBrokerOrderId_escalatesNoBracketId() {
        ExecutorPosition p = openPosition(9L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, null, 1, null, null);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        DecisionLog log = logCaptor.getValue();
        assertThat(log.action()).isEqualTo("ESCALATE");
        assertThat(log.reasonCode()).isEqualTo("NO_BRACKET_ID");
        // Which id is missing, as a field: NO_BRACKET_ID also covers "this leg has neither its
        // own entry id nor the position's", which is a different repair.
        assertThat(log.inputsSnapshot().path("missing").asString())
                .isEqualTo("POSITION_BROKER_ORDER_ID");
        assertThat(log.orderJson()).isNotNull();
        assertThat(log.orderJson().get("position_id").asLong()).isEqualTo(9L);
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
        // No "stop raised" push may go out when the stop did not move.
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void chandelierRounded_buyDown() {
        // 110 - 3.0*2.001 = 103.997 -> FLOOR at 2dp -> 103.99 (never above the computed level).
        ExecutorPosition p = openPosition(10L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.001")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).stop()).isEqualByComparingTo("103.99");

        ArgumentCaptor<BigDecimal> newStopCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(10L),
                any(), any(), any(Integer.class), newStopCaptor.capture(), any(), any());
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
        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.001")),
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

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
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

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("100")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        verify(decisionRepo, never()).insert(any());
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void tranche2_wrongSideChandelier_skipsSilently() {
        // Pins the order of the two gates: the market-side skip runs BEFORE the tranche-2
        // escalation, so a position that is both unsendable AND tranche-2 produces no row at all.
        // It gets its escalation on the next run where the price is back above the chandelier.
        ExecutorPosition p = openPosition(14L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "t2-1", null);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
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

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")), Map.of(), "run1");

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

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.001")),
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

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
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

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
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

        ratchet(List.of(tranche2, noBracket, healthy),
                Map.of("AAA", new BigDecimal("2.0"), "BBB", new BigDecimal("2.0"),
                        "CCC", new BigDecimal("2.0")),
                Map.of("AAA", new BigDecimal("110"), "BBB", new BigDecimal("110"),
                        "CCC", new BigDecimal("110")),
                "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).symbol()).isEqualTo("CCC");
        assertThat(gateway.modifyCalls.get(0).orderId()).isEqualTo("brk-1");
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(21L),
                any(), any(), any(Integer.class), any(), any(), any());
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

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);
        assertThat(service.backoffs).containsExactly(500L);
        // No escalation, and the book gets the new stop the broker confirmed on attempt 2.
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("MODIFY_STOP");
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(30L),
                any(), any(), any(Integer.class), any(), any(), any());
        verify(executorNotifier).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void transientRateLimit_exhaustsAttemptsThenEscalates() {
        ExecutorPosition p = openPosition(31L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);
        gateway.modifyFailures = 99;
        gateway.modifyFailureMessage = RATE_LIMITED;

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
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

        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
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

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
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

            ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
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

            ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
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
        gateway.modifyRejectCode = "LEG_NOT_FOUND";

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(service.backoffs).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("STOP_LEG_MISSING");
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

        ratchet(List.of(a, b),
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

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(service.backoffs).isEmpty();
    }

    // -------------------------------------------------------------------
    // The ratchet over executor_position_leg — one row per broker tranche
    // -------------------------------------------------------------------

    @Test
    void ratchetsEveryOpenLegWithItsExplicitStopId() {
        // BUG-S13. The broker holds each tranche as its own position with its own stop order.
        // Every one of them has to be named: Agora's by-symbol fallback keeps only the LAST stop
        // order on the instrument, so an unnamed modify with two live legs moves one of them —
        // picked by the broker's scan order — while the book would record the new stop for both.
        ExecutorPosition p = openPosition(60L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "ord-1", 2, "ord-2", "stop-2");
        withOpenLegs(60L,
                leg(10L, 60L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 60L, 2, "ord-2", "stop-2", new BigDecimal("10")));

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);
        assertThat(gateway.modifyCalls).extracting(FakeExecutionGateway.ModifyCall::stopOrderId)
                .containsExactlyInAnyOrder("stop-1", "stop-2");
        assertThat(gateway.modifyCalls).noneMatch(c -> c.stopOrderId() == null);
        // Each leg is addressed through its OWN entry order id, not the position's bracket for both.
        assertThat(gateway.modifyCalls).extracting(FakeExecutionGateway.ModifyCall::orderId)
                .containsExactly("ord-1", "ord-2");
        assertThat(gateway.modifyCalls).allSatisfy(
                c -> assertThat(c.stop()).isEqualByComparingTo("104"));

        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(60L),
                any(), any(), any(Integer.class),
                org.mockito.ArgumentMatchers.argThat(v -> v.compareTo(new BigDecimal("104")) == 0),
                org.mockito.ArgumentMatchers.isNull(), any());
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("MODIFY_STOP");
        verify(executorNotifier).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void legsOutrankTheTrancheColumns() {
        // The columns say "one tranche, stop-1"; the leg table says two live legs. The legs win:
        // they are the book's record of what the broker actually holds, and a column that has not
        // caught up must never shrink the ratchet to one leg.
        ExecutorPosition p = openPosition(61L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 1, null, null);
        withOpenLegs(61L,
                leg(10L, 61L, 1, null, "stop-1", new BigDecimal("10")),
                leg(11L, 61L, 2, null, "stop-2", new BigDecimal("10")));

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);
        // No entry order id on either leg -> the position's bracket id is the order id, and the
        // leg id is still what selects the leg.
        assertThat(gateway.modifyCalls).extracting(FakeExecutionGateway.ModifyCall::orderId)
                .containsExactly("brk-1", "brk-1");
        assertThat(gateway.modifyCalls).extracting(FakeExecutionGateway.ModifyCall::stopOrderId)
                .containsExactly("stop-1", "stop-2");
    }

    @Test
    void aFilledLegIsNeverAddressed_soTheOrdinaryCaseIsSilent() {
        // Requirement for STOP_LEG_MISSING not to become noise: reconcile runs BEFORE the ratchet
        // in the same pass and closes every leg whose stop it saw fill. The ratchet reads the OPEN
        // legs fresh, so the filled leg is simply not in the list — one modify, for the survivor,
        // and no escalation anywhere. The columns still name the dead leg (stop-1); that must not
        // produce a single call.
        ExecutorPosition p = openPosition(62L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "ord-1", 2, "ord-2", "stop-2");
        withOpenLegs(62L, leg(11L, 62L, 2, "ord-2", "stop-2", new BigDecimal("10")));

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).stopOrderId()).isEqualTo("stop-2");
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo("MODIFY_STOP");
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(62L),
                any(), any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void legNotFound_escalatesAsStopLegMissingNotBrokerUnavailable() {
        // The book still holds this leg OPEN and the broker does not have it: that is the state
        // that must be visible. It is a verdict, not an outage — BROKER_UNAVAILABLE has to keep
        // meaning "no answer from the broker" or an operator cannot read either alarm.
        ExecutorPosition p = openPosition(63L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "ord-1", 1, null, null);
        withOpenLegs(63L, leg(10L, 63L, 1, "ord-1", "stop-1", new BigDecimal("10")));
        gateway.modifyFailures = 99;
        gateway.modifyRejectCode = "LEG_NOT_FOUND";
        gateway.modifyFailureMessage = "agora order rejected [LEG_NOT_FOUND]: no working order stop-1";

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(d -> "STOP_LEG_MISSING".equals(d.reasonCode()));
        assertThat(captor.getAllValues()).noneMatch(d -> "BROKER_UNAVAILABLE".equals(d.reasonCode()));
        assertThat(captor.getAllValues()).allSatisfy(d -> assertThat(d.action()).isEqualTo("ESCALATE"));
        // Structural: one attempt, no backoff, and the book keeps the old stop.
        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(service.backoffs).isEmpty();
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void aTransportFailureOnALegIsStillBrokerUnavailable() {
        // The other half of the same distinction: no verdict from the broker at all keeps
        // BROKER_UNAVAILABLE. Without this the new code would just be a rename.
        ExecutorPosition p = openPosition(64L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "ord-1", 1, null, null);
        withOpenLegs(64L, leg(10L, 64L, 1, "ord-1", "stop-1", new BigDecimal("10")));
        gateway.modifyFailures = 99;
        gateway.modifyFailureMessage = "agora trading call failed: modify_bracket — HTTP 503";
        service = newService(1, 0L, 0L);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        assertThat(logCaptor.getValue().reasoning()).contains("stop-1");
    }

    @Test
    void aRejectionThatIsNotLegNotFound_isReportedAsARejection() {
        // Any accepted:false verdict is a rejection, not an outage — but nothing may claim the leg
        // is gone on a code that does not say so.
        ExecutorPosition p = openPosition(65L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "ord-1", 1, null, null);
        withOpenLegs(65L, leg(10L, 65L, 1, "ord-1", "stop-1", new BigDecimal("10")));
        gateway.modifyFailures = 99;
        gateway.modifyRejectCode = "PRICE_OUT_OF_RANGE";
        gateway.modifyFailureMessage = "agora order rejected [PRICE_OUT_OF_RANGE]: outside collar";

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("STOP_MODIFY_REJECTED");
        assertThat(logCaptor.getValue().reasoning()).contains("PRICE_OUT_OF_RANGE").contains("stop-1");
        assertThat(gateway.modifyCalls).hasSize(1);
    }

    @Test
    void activeStopIsNotWrittenWhenOneLegFails() {
        // Half a broker is not a book entry: leg 1 sits at 104, leg 2 still at 95, so the only
        // level true of the WHOLE position is 95. stopguard reads that single column.
        ExecutorPosition p = openPosition(66L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "ord-1", 2, "ord-2", "stop-2");
        withOpenLegs(66L,
                leg(10L, 66L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 66L, 2, "ord-2", "stop-2", new BigDecimal("10")));
        gateway.modifyFailures = 1;
        gateway.failModifyForStopOrderId = "stop-2";
        gateway.modifyRejectCode = "LEG_NOT_FOUND";
        gateway.modifyFailureMessage = "agora order rejected [LEG_NOT_FOUND]: no working order stop-2";
        service = newService(1, 0L, 0L);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        // active_stop must NOT move — but broker_stop does, because leg 1 really rests at the new
        // level now (see partialLegRatchetPersistsBrokerStopOfTheConfirmedLeg). With bufferAtr = 0
        // the two prices coincide at 104.00, so this asserts the active_stop column specifically.
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(66L), any(), any(),
                any(Integer.class),
                org.mockito.ArgumentMatchers.argThat(
                        (BigDecimal v) -> v != null && v.compareTo(new BigDecimal("95")) == 0),
                org.mockito.ArgumentMatchers.isNull(), any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, times(2)).insert(logCaptor.capture());
        List<DecisionLog> logs = logCaptor.getAllValues();
        assertThat(logs.get(0).reasonCode()).isEqualTo("STOP_LEG_MISSING");
        assertThat(logs.get(1).reasonCode()).isEqualTo("PARTIAL_TRANCHE_RATCHET");
        assertThat(logs.get(1).orderJson().get("moved_stop_order_ids").get(0).asString())
                .isEqualTo("stop-1");
        assertThat(logs.get(1).orderJson().get("unmoved_stop_order_id").asString()).isEqualTo("stop-2");
        assertThat(logs.get(1).orderJson().get("active_stop").asDouble()).isEqualTo(95.0);
        assertThat(logs.get(1).orderJson().get("attempted_stop").asDouble()).isEqualTo(104.0);
        assertThat(logs.get(1).orderJson().get("position_id").asLong()).isEqualTo(66L);
    }

    @Test
    void firstLegFails_isNotReportedAsAPartial() {
        // Nothing moved at the broker, so this is an ordinary failed ratchet: one escalation, no
        // second modify, and emphatically not a "partial".
        ExecutorPosition p = openPosition(67L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "ord-1", 2, "ord-2", "stop-2");
        withOpenLegs(67L,
                leg(10L, 67L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 67L, 2, "ord-2", "stop-2", new BigDecimal("10")));
        gateway.modifyFailures = 99;
        gateway.failModifyForStopOrderId = "stop-1";
        service = newService(1, 0L, 0L);

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void twoOpenLegsWithOneUnnamedLeg_escalatesAndSendsNothing() {
        // A leg that cannot be named cannot be moved while a sibling is live: an unnamed modify
        // would land in the by-symbol fallback and patch the OTHER leg twice. Escalate, send
        // nothing, leave every stop where it is.
        ExecutorPosition p = openPosition(68L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "ord-1", 2, "ord-2", null);
        withOpenLegs(68L,
                leg(10L, 68L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 68L, 2, "ord-2", null, new BigDecimal("10")));

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
        // The LEG path -- the same reason code as the legacy column path above, told apart by a
        // field rather than by reading the prose. The two conditions are different and the column
        // one dies when the legless fallback does.
        assertThat(logCaptor.getValue().inputsSnapshot().path("path").asString()).isEqualTo("LEG");
        assertThat(logCaptor.getValue().reasoning()).contains("tranche 2");
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void aSingleOpenLegWithNoStopIdStillRatchetsThroughTheBracket() {
        // The one unnamed modify that stays legitimate: with a single stop live on the instrument
        // "the last stop order found" and "the only stop order" are the same order. Escalating
        // instead would leave a real position un-ratcheted for as long as the id is missing.
        ExecutorPosition p = openPosition(69L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 1, null, null);
        withOpenLegs(69L, leg(10L, 69L, 1, null, null, new BigDecimal("10")));

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).orderId()).isEqualTo("brk-1");
        assertThat(gateway.modifyCalls.get(0).stopOrderId()).isNull();
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(69L),
                any(), any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void aSingleUnnamedOpenLegOnATwoTranchePosition_escalatesInsteadOfGuessing() {
        // Legs are seeded when reconcile OBSERVES a tranche's working stop, so between a
        // tranche-2 fill and the next reconcile pass the book legitimately shows ONE open leg
        // while the broker already works TWO stops. The book's leg count therefore cannot license
        // the unnamed modify: Agora's by-symbol fallback keeps the LAST stop it scans, so this
        // would move a stop we did not choose and record a full success for the whole position.
        // The position's own record of a second tranche is what decides.
        ExecutorPosition p = openPosition(71L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "ord-2", "stop-2");
        withOpenLegs(71L, leg(10L, 71L, 1, null, null, new BigDecimal("6")));

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
        // active_stop must not move: the book may not claim a protection level the broker was
        // never asked for.
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void aTwoTranchePositionDownToOneSurvivingLeg_stillRatchetsThroughTheBracket() {
        // The collapsed survivor. Its sibling tranche has a leg row and that row is CLOSED, so
        // the broker works exactly one stop and the by-symbol fallback is unambiguous again.
        // Escalating here every pass would be the same self-inflicted loop as BUG-S13.
        ExecutorPosition p = openPosition(72L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "ord-2", "stop-2");
        withOpenLegs(72L, leg(10L, 72L, 1, null, null, new BigDecimal("6")));
        when(legRepo.findByPosition(72L)).thenReturn(List.of(
                leg(10L, 72L, 1, null, null, new BigDecimal("6")),
                new ExecutorPositionLeg(11L, 72L, 2, "ord-2", "stop-2", new BigDecimal("4"),
                        ExecutorPositionLeg.CLOSED, new BigDecimal("95"), "HARD_STOP", null)));

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).stopOrderId()).isNull();
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(72L),
                any(), any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void aTwoTranchePositionWhoseSiblingLegWasNeverRecorded_stillEscalates() {
        // Absence of a leg row is NOT evidence the sibling stop is gone: a position whose
        // tranche2_stop_order_id was never recorded can never have its second leg seeded while
        // the broker goes on working two stops. Silence must keep escalating.
        ExecutorPosition p = openPosition(73L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "ord-2", null);
        withOpenLegs(73L, leg(10L, 73L, 1, null, null, new BigDecimal("6")));
        when(legRepo.findByPosition(73L)).thenReturn(List.of(
                leg(10L, 73L, 1, null, null, new BigDecimal("6"))));

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("TRANCHE_RATCHET_UNSUPPORTED");
    }

    @Test
    void aLegWithNoAddressAtAll_escalatesNoBracketId() {
        // No entry order id on the leg and no bracket id on the position: there is nothing to send
        // the modify to. Escalate rather than call the gateway with a null order id.
        ExecutorPosition p = openPosition(70L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, null, 1, null, null);
        withOpenLegs(70L, leg(10L, 70L, 1, null, "stop-1", new BigDecimal("10")));

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).isEmpty();
        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("NO_BRACKET_ID");
        verify(positionRepo, never()).updateMaintenance(anyLong(), any(), any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void stopLegMissing_namesTheLegAndTheAttemptCount() {
        // Operator-facing text, pinned: the previous version asserted only the reason code, which
        // is exactly why a null-interpolating sentence shipped green. A named leg knows WHICH leg
        // the broker no longer has, and the row says so, with the attempt count both sibling
        // branches carry.
        ExecutorPosition p = openPosition(72L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "ord-1", 1, null, null);
        withOpenLegs(72L, leg(10L, 72L, 1, "ord-1", "stop-1", new BigDecimal("10")));
        gateway.modifyFailures = 99;
        gateway.modifyRejectCode = "LEG_NOT_FOUND";
        gateway.modifyFailureMessage = "agora order rejected [LEG_NOT_FOUND]: no working order stop-1";

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        String reasoning = logCaptor.getValue().reasoning();
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("STOP_LEG_MISSING");
        assertThat(reasoning).contains("stop leg stop-1")
                .contains("no longer exists at the broker")
                .contains("the book still holds it open")
                .contains("after 1 attempt: ")
                .contains("LEG_NOT_FOUND");
    }

    @Test
    void stopLegMissing_onAnUnnamedModify_claimsOnlyWhatThatCaseKnows() {
        // The single-tranche/bracket-addressed case: LEG_NOT_FOUND here means the broker resolved
        // NO stop leg from the bracket. It does NOT mean "the leg the book names is gone" — the
        // book names none — and the row must not assert it. It must also never read
        // "stop leg null ...": this repo interpolates no nulls into operator text.
        ExecutorPosition p = openPosition(73L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);
        gateway.modifyFailures = 99;
        gateway.modifyRejectCode = "LEG_NOT_FOUND";
        gateway.modifyFailureMessage = "agora order rejected [LEG_NOT_FOUND]: no stop-loss leg";

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logCaptor.capture());
        String reasoning = logCaptor.getValue().reasoning();
        assertThat(logCaptor.getValue().reasonCode()).isEqualTo("STOP_LEG_MISSING");
        assertThat(reasoning).doesNotContain("null");
        assertThat(reasoning).contains("resolved no stop leg for this bracket")
                .contains("after 1 attempt: ")
                .contains("LEG_NOT_FOUND");
        // And it must not claim the stronger fact the named case has.
        assertThat(reasoning).doesNotContain("still holds it open");
    }

    @Test
    void anUnaddressableLegAfterAnEarlierOneMoved_isStillRecordedAsAPartial() {
        // Half-moved is half-moved whatever stopped the second leg. The NO_BRACKET_ID row names
        // the cause but not the state, so the partial row has to be written here too — otherwise
        // the broker sits at two different stop levels with nothing in the log saying so.
        ExecutorPosition p = openPosition(74L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, null, 2, null, "stop-2");
        withOpenLegs(74L,
                leg(10L, 74L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 74L, 2, null, "stop-2", new BigDecimal("10")));

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        // Leg 1 had an entry order id and moved; leg 2 has neither its own nor a bracket id.
        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).stopOrderId()).isEqualTo("stop-1");

        ArgumentCaptor<DecisionLog> logCaptor = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, times(2)).insert(logCaptor.capture());
        List<DecisionLog> logs = logCaptor.getAllValues();
        assertThat(logs.get(0).reasonCode()).isEqualTo("NO_BRACKET_ID");
        // The OTHER NO_BRACKET_ID condition, distinguished by a field rather than by prose.
        assertThat(logs.get(0).inputsSnapshot().path("missing").asString())
                .isEqualTo("LEG_ENTRY_ORDER_ID_AND_POSITION_BROKER_ORDER_ID");
        assertThat(logs.get(0).inputsSnapshot().path("tranche").asInt()).isEqualTo(2);
        assertThat(logs.get(1).reasonCode()).isEqualTo("PARTIAL_TRANCHE_RATCHET");
        assertThat(logs.get(1).orderJson().get("moved_stop_order_ids").get(0).asString())
                .isEqualTo("stop-1");
        assertThat(logs.get(1).orderJson().get("unmoved_stop_order_id").asString()).isEqualTo("stop-2");
        assertThat(logs.get(1).orderJson().get("active_stop").asDouble()).isEqualTo(95.0);
        // The partial path writes broker_stop -- the leg that moved really rests there -- but
        // active_stop must stay at the level EVERY share still honours.
        verify(positionRepo).updateMaintenance(anyLong(), any(), any(), any(Integer.class),
                org.mockito.ArgumentMatchers.argThat(
                        (BigDecimal v) -> v != null && v.compareTo(new BigDecimal("95")) == 0),
                org.mockito.ArgumentMatchers.isNull(), any());
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());
    }

    @Test
    void aTransientFailureOnALegIsStillRetried() {
        // The retry contract is unchanged by the leg rewrite: a rate limit is "come back in a
        // moment", and the second attempt confirms the leg.
        ExecutorPosition p = openPosition(71L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "ord-1", 1, null, null);
        withOpenLegs(71L, leg(10L, 71L, 1, "ord-1", "stop-1", new BigDecimal("10")));
        gateway.modifyFailures = 1;
        gateway.modifyFailureMessage = RATE_LIMITED;

        ratchet(List.of(p), Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);
        assertThat(gateway.modifyCalls).allSatisfy(
                c -> assertThat(c.stopOrderId()).isEqualTo("stop-1"));
        assertThat(service.backoffs).containsExactly(500L);
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(71L),
                any(), any(), any(Integer.class), any(), any(), any());
    }

    private static ExecutorPosition withBrokerStop(ExecutorPosition p, BigDecimal brokerStop) {
        return new ExecutorPosition(p.id(), p.connection(), p.symbol(), p.side(), p.qty(),
                p.entryPrice(), p.initialStop(), p.activeStop(), p.tranche(), p.rValue(),
                p.killCriteria(), p.sourceSignalId(), p.sourceAgent(), p.entryDate(), p.mfe(),
                p.status(), p.brokerOrderId(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                p.exitPrice(), p.realizedR(), p.exitReason(), p.closedAt(), p.stopOrderId(),
                p.sector(), p.entryDayHigh(), p.tranche2OrderId(), p.tranche2StopOrderId(),
                p.trimCount(), p.lowestPrice(), p.entryExpiresAt(), p.submittedLimitPrice(),
                p.pendingExitReason(), p.exitOrderId(), p.pendingExitFillPrice(),
                p.stopLegsCollapsed(), brokerStop, p.entryFilledAt());
    }

    /** Test 15. The broker leg rests a buffer BELOW the logical chandelier, and the book records
     *  the logical chandelier as active_stop plus the buffered price as broker_stop.
     *  Mutation: send the raw chandelier. */
    @Test
    void ratchetSendsBufferedChandelier() {
        service = newService(3, 500L, 10_000L, BigDecimal.ONE);
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        // chandelier = 110 - 3 * 2.0 = 104.00; broker = 104.00 - 1 * 2.0 = 102.00
        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).stop()).isEqualByComparingTo("102.00");
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("110")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("1.0")),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.argThat(
                        (BigDecimal v) -> v != null && v.compareTo(new BigDecimal("104.00")) == 0),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.argThat(
                        (BigDecimal v) -> v != null && v.compareTo(new BigDecimal("102.00")) == 0));
    }

    /** Test 16. The guard decides on the LOGICAL pair (active_stop -> chandelier), never on
     *  broker prices. Mutation: run the guard on the buffered price — the buffered chandelier here
     *  is BELOW the current active_stop, so a broker-price guard would deny a ratchet that is
     *  genuinely an improvement. */
    @Test
    void guardStillComparesLogicalStops() {
        service = newService(3, 500L, 10_000L, BigDecimal.ONE);
        // chandelier = 110 - 6 = 104.00, an improvement on active_stop 103.
        // broker = 104.00 - 2.0 = 102.00, which is BELOW 103. The leg already rests at 102.00, so
        // the monotonic floor does not lift it back up and the buffered price really is sent.
        ExecutorPosition p = withBrokerStop(openPosition(1L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("103"), new BigDecimal("1.0"), 0), new BigDecimal("102.00"));

        service.ratchet(List.of(p),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).stop()).isEqualByComparingTo("102.00");
    }

    /** Test 18. One price level for the whole position: both legs get the SAME buffered value.
     *  Mutation: compute a per-leg ATR / a per-leg buffer. */
    @Test
    void bothLegsGetTheSameBufferedPrice() {
        service = newService(3, 500L, 10_000L, BigDecimal.ONE);
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "brk-1", 2, "brk-2", "stop-2");
        withOpenLegs(1L,
                leg(11L, 1L, 1, "brk-1", "stop-1", new BigDecimal("5")),
                leg(12L, 1L, 2, "brk-2", "stop-2", new BigDecimal("5")));

        service.ratchet(List.of(p),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        assertThat(gateway.modifyCalls).hasSize(2);
        assertThat(gateway.modifyCalls).extracting(FakeExecutionGateway.ModifyCall::stop)
                .allSatisfy(s -> assertThat(s).isEqualByComparingTo("102.00"));
        assertThat(gateway.modifyCalls).extracting(FakeExecutionGateway.ModifyCall::stopOrderId)
                .containsExactlyInAnyOrder("stop-1", "stop-2");
    }

    /** The monotonic floor: ATR expanding faster than the high must never walk the LIVE leg down.
     *  Mutation: drop the max in BrokerStop.forRatchet (also covered by test 12, here end to end).
     *  The broker leg stays where it is while active_stop still advances. */
    @Test
    void expandingAtrLeavesTheBrokerLegWhereItIsWhileActiveStopAdvances() {
        service = newService(3, 500L, 10_000L, BigDecimal.ONE);
        ExecutorPosition base = openPosition(1L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("103"), new BigDecimal("1.0"), 0);
        ExecutorPosition p = withBrokerStop(base, new BigDecimal("103.50"));

        service.ratchet(List.of(p),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        // chandelier 104.00 (an improvement on 103), buffered 102.00 — but the leg already rests
        // at 103.50, so the leg stays and only the logical stop moves.
        assertThat(gateway.modifyCalls.get(0).stop()).isEqualByComparingTo("103.50");
        ArgumentCaptor<DecisionLog> logs = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo).insert(logs.capture());
        assertThat(logs.getValue().inputsSnapshot().path("broker_stop_lags").asBoolean()).isTrue();
        assertThat(logs.getValue().inputsSnapshot().path("broker_stop_old").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("103.50"));
        assertThat(logs.getValue().inputsSnapshot().path("broker_stop_new").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("103.50"));
        assertThat(logs.getValue().inputsSnapshot().path("atr_effective").decimalValue())
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("2.0"));
    }

    /** The ratchet computes the chandelier from atrEff, not from ATR22.
     *  Mutation: pass atrBySymbol to computeChandelier. */
    @Test
    void chandelierUsesAtrEffNotAtr22() {
        service = newService(3, 500L, 10_000L, BigDecimal.ZERO);
        ExecutorPosition p = openPosition(1L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0);

        service.ratchet(List.of(p),
                Map.of("ACME", new BigDecimal("2.0")),    // atr22
                Map.of("ACME", new BigDecimal("4.0")),    // atr_short
                Map.of("ACME", new BigDecimal("4.0")),    // atrEff = max(2.0, 4.0)
                Map.of("ACME", new BigDecimal("110")), "run1");

        // With atrEff: 110 - 3 * 4.0 = 98.00. With atr22 it would be 104.00.
        assertThat(gateway.modifyCalls.get(0).stop()).isEqualByComparingTo("98.00");
    }

    /** Test 17 (Variant A — the probe showed Saxo accepts a same-price ReplaceOrder). A permitted
     *  ratchet whose broker price is unchanged STILL sends the modify: the call count is identical
     *  to pre-SP1 (the guard gates it the same way, so rate-limit pressure does not rise), and on
     *  permitted runs the modify doubles as a leg liveness check — a LEG_NOT_FOUND is how a leg the
     *  book believes is live becomes visible.
     *  Mutation: skip the call when the price is unchanged. */
    @Test
    void unchangedBrokerPriceStillSendsTheModify() {
        service = newService(3, 500L, 10_000L, BigDecimal.ONE);
        ExecutorPosition p = withBrokerStop(openPosition(1L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("103"), new BigDecimal("1.0"), 0), new BigDecimal("103.50"));

        service.ratchet(List.of(p),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        // Buffered chandelier 102.00 < the resting 103.50 -> the leg keeps its price, and the
        // modify is sent at that unchanged price anyway.
        assertThat(gateway.modifyCalls).hasSize(1);
        assertThat(gateway.modifyCalls.get(0).stop()).isEqualByComparingTo("103.50");
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(1L), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.argThat(
                        (BigDecimal v) -> v != null && v.compareTo(new BigDecimal("104.00")) == 0),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.argThat(
                        (BigDecimal v) -> v != null && v.compareTo(new BigDecimal("103.50")) == 0));
    }

    /** A partial ratchet is broker-confirmed for the leg that DID move: it now rests at the
     *  buffered price, so broker_stop records it while active_stop stays at the old level (one leg
     *  is still there) and the PARTIAL_TRANCHE_RATCHET escalation stays.
     *  Mutation: keep persisting nothing on the partial path. */
    @Test
    void partialLegRatchetPersistsBrokerStopOfTheConfirmedLeg() {
        service = newService(1, 0L, 0L, BigDecimal.ONE);
        ExecutorPosition p = openPosition(66L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "ord-1", 2, "ord-2", "stop-2");
        withOpenLegs(66L,
                leg(10L, 66L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 66L, 2, "ord-2", "stop-2", new BigDecimal("10")));
        gateway.modifyFailures = 1;
        gateway.failModifyForStopOrderId = "stop-2";
        gateway.modifyRejectCode = "LEG_NOT_FOUND";
        gateway.modifyFailureMessage = "agora order rejected [LEG_NOT_FOUND]: no working order stop-2";

        service.ratchet(List.of(p),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        // chandelier 104.00, buffered 102.00 — leg 1 confirmed there, leg 2 rejected.
        assertThat(gateway.modifyCalls).hasSize(2);
        verify(positionRepo).updateMaintenance(org.mockito.ArgumentMatchers.eq(66L),
                any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                // active_stop stays at the old level: one leg is still resting there.
                org.mockito.ArgumentMatchers.argThat(
                        (BigDecimal v) -> v != null && v.compareTo(new BigDecimal("95")) == 0),
                org.mockito.ArgumentMatchers.isNull(),
                // broker_stop follows the leg that moved, so the next run's monotonic floor
                // cannot sit below a live leg.
                org.mockito.ArgumentMatchers.argThat(
                        (BigDecimal v) -> v != null && v.compareTo(new BigDecimal("102.00")) == 0));
        verify(executorNotifier, never()).notifyStopRatchet(any(), any(), any(), any());

        ArgumentCaptor<DecisionLog> logs = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionRepo, times(2)).insert(logs.capture());
        assertThat(logs.getAllValues().get(1).reasonCode()).isEqualTo("PARTIAL_TRANCHE_RATCHET");
    }

    /** The follow-up run to the partial above: the value run 1 persisted is what keeps run 2 from
     *  sending a price BELOW the leg run 1 confirmed. Driven end to end (run 1's persisted
     *  broker_stop is captured and fed to run 2) so the two halves cannot drift apart.
     *  Mutation: persist nothing on the partial path — run 2's floor falls back to active_stop 95
     *  and walks the live leg down from 102.00 to 94.00. */
    @Test
    void nextRunNeverSendsBelowTheLegAPartialRatchetConfirmed() {
        service = newService(1, 0L, 0L, BigDecimal.ONE);
        ExecutorPosition p = openPosition(66L, "ACME", "BUY", new BigDecimal("110"),
                new BigDecimal("95"), new BigDecimal("1.0"), 0, "ord-1", 2, "ord-2", "stop-2");
        withOpenLegs(66L,
                leg(10L, 66L, 1, "ord-1", "stop-1", new BigDecimal("10")),
                leg(11L, 66L, 2, "ord-2", "stop-2", new BigDecimal("10")));
        gateway.modifyFailures = 1;
        gateway.failModifyForStopOrderId = "stop-2";

        service.ratchet(List.of(p),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("2.0")),
                Map.of("ACME", new BigDecimal("110")), "run1");

        ArgumentCaptor<BigDecimal> persistedBrokerStop = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionRepo).updateMaintenance(anyLong(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(),
                org.mockito.ArgumentMatchers.isNull(), persistedBrokerStop.capture());
        assertThat(persistedBrokerStop.getValue()).isEqualByComparingTo("102.00");

        // Run 2: the broker cooperates again, but ATR has doubled, so the raw buffered price
        // (110 - 3*4.0 = 98.00, less 1 x 4.0 = 94.00) is BELOW where leg 1 already rests.
        gateway.modifyCalls.clear();
        gateway.modifyFailures = 0;
        gateway.failModifyForStopOrderId = null;
        ExecutorPosition afterPartial = withBrokerStop(p, persistedBrokerStop.getValue());

        service.ratchet(List.of(afterPartial),
                Map.of("ACME", new BigDecimal("4.0")),
                Map.of("ACME", new BigDecimal("4.0")),
                Map.of("ACME", new BigDecimal("4.0")),
                Map.of("ACME", new BigDecimal("110")), "run2");

        assertThat(gateway.modifyCalls).hasSize(2);
        assertThat(gateway.modifyCalls).extracting(FakeExecutionGateway.ModifyCall::stop)
                .allSatisfy(stop -> assertThat(stop).isEqualByComparingTo("102.00"));
    }
}
