package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.CloseResult;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.executor.broker.RestoredLeg;
import de.visterion.dracul.notify.TelegramNotifier;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 2026-10-03 §5.7 — the shared partial exit. P1 #1/#2/#9, 4d. */
class PartialExitServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-08T23:00:00Z");

    private final ExecutionGateway gateway = mock(ExecutionGateway.class);
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final ExecutorPositionLegRepository legRepo = mock(ExecutorPositionLegRepository.class);
    private final DecisionLogRepository decisionLogRepo = mock(DecisionLogRepository.class);
    private final RuleVersionProvider ruleVersions = mock(RuleVersionProvider.class);
    private final TelegramNotifier telegram = mock(TelegramNotifier.class);

    private PartialExitService service;

    @BeforeEach
    void setUp() {
        when(ruleVersions.active()).thenReturn("exec-test");
        service = new PartialExitService(gateway, positionRepo, legRepo, decisionLogRepo,
                ruleVersions, new ObjectMapper(), telegram, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExecutorPosition position(long id, String qty, int trimCount, String stopOrderId,
            String tranche2StopOrderId) {
        return ExecutorPositionFixtures.conviction(ExecutorPositionFixtures.withoutKillLevel(id,
                "depot-1", "SYNC", "BUY", new BigDecimal(qty), new BigDecimal("100"),
                new BigDecimal("65"), new BigDecimal("65"), tranche2StopOrderId == null ? 1 : 2,
                null, List.of(), "sig-1", "strigoi-tech", "2026-07-01", null, "OPEN", "brk-1",
                new BigDecimal("131"), null, 0, null, null, null, null, stopOrderId, null, null,
                tranche2StopOrderId == null ? null : "brk-2", tranche2StopOrderId, trimCount, null,
                null, null, null, null, null, false, new BigDecimal("65"), "2026-07-01T09:00:00Z"));
    }

    private static ExecutorPositionLeg leg(long id, long positionId, int tranche, String stopOrderId,
            String qty) {
        return new ExecutorPositionLeg(id, positionId, tranche, "brk-" + tranche, stopOrderId,
                new BigDecimal(qty), ExecutorPositionLeg.OPEN, null, null, null);
    }

    private DecisionLog onlyRow() {
        ArgumentCaptor<DecisionLog> c = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo).insert(c.capture());
        return c.getValue();
    }

    /** P1 #1 (†): an accepted partial close repoints the LEG ROWS to protective_legs.order_id —
     *  today only the position columns move and the ratchet addresses a dead leg forever. */
    @Test
    void acceptedTrimRepointsTheLegRowsToTheRestoredIds() {
        ExecutorPosition p = position(5L, "10", 0, "stop-old", null);
        when(legRepo.findOpenByPosition(5L)).thenReturn(List.of(leg(50L, 5L, 1, "stop-old", "10")));
        when(gateway.flatten("depot-1", "SYNC", new BigDecimal("0.5"))).thenReturn(new CloseResult(
                new BigDecimal("5"), new BigDecimal("5"), null, "trim-ord-1",
                List.of(new RestoredLeg("stop-old", "stop-new", new BigDecimal("5"), new BigDecimal("65"))),
                false));

        PartialExitService.Result r = service.execute(p, new BigDecimal("0.5"), "HARD_TRIGGER",
                "HARD_TARGET_HALF", "target-half flatten", null, null, "run-1");

        assertThat(r.trimmed()).isTrue();
        verify(positionRepo).recordTrim(eq(5L), eq(new BigDecimal("5")), eq(1), any(), eq(false));
        verify(legRepo).repointLegStop(50L, "stop-new");
        verify(legRepo, never()).closeLeg(anyLong(), any(), any(), any());
    }

    /** 4d (R2 Minor 3): a two-leg trim that collapsed to ONE restored leg CLOSES the dead leg —
     *  a nulled-but-OPEN leg would raise LEG_QTY_DESYNC + TRANCHE_RATCHET_UNSUPPORTED every pass. */
    @Test
    void acceptedCollapsedTrimClosesTheDeadLeg() {
        ExecutorPosition p = position(6L, "4", 0, "stop-a", "stop-b");
        when(legRepo.findOpenByPosition(6L)).thenReturn(List.of(
                leg(60L, 6L, 1, "stop-a", "2"), leg(61L, 6L, 2, "stop-b", "2")));
        when(gateway.flatten(eq("depot-1"), eq("SYNC"), any())).thenReturn(new CloseResult(
                new BigDecimal("3"), new BigDecimal("1"), null, "trim-ord-2",
                List.of(new RestoredLeg("stop-a", "stop-a2", BigDecimal.ONE, new BigDecimal("65"))),
                true));

        service.execute(p, new BigDecimal("0.5"), "SOFT_TRIGGER", null, "soft-exit flatten",
                "r", 0.7, "run-1");

        verify(legRepo).repointLegStop(60L, "stop-a2");
        verify(legRepo).closeLeg(eq(61L), isNull(), eq("TRIM"), eq(NOW));
        verify(legRepo, never()).repointLegStop(eq(61L), any());
    }

    /** P1 #9 + §5.7: no fill price => pending_trim_order_id is stored and the TRIM row carries
     *  the order id (so reconcile can backfill the price). */
    @Test
    void acceptedTrimWithoutPriceStoresThePendingMarkerAndTheOrderId() {
        ExecutorPosition p = position(7L, "10", 0, "stop-old", null);
        when(gateway.flatten(eq("depot-1"), eq("SYNC"), any())).thenReturn(new CloseResult(
                new BigDecimal("5"), new BigDecimal("5"), null, "trim-ord-3", List.of(), false));

        service.execute(p, new BigDecimal("0.5"), "HARD_TRIGGER", "HARD_TARGET_HALF",
                "target-half flatten", null, null, "run-1");

        verify(positionRepo).markPendingTrim(7L, "trim-ord-3");
        DecisionLog row = onlyRow();
        assertThat(row.action()).isEqualTo("TRIM");
        assertThat(row.triggerType()).isEqualTo("HARD_TRIGGER");
        assertThat(row.reasonCode()).isEqualTo("HARD_TARGET_HALF");
        assertThat(row.orderJson().path("order_id").asString()).isEqualTo("trim-ord-3");
        assertThat(row.orderJson().path("position_id").asLong()).isEqualTo(7L);
        assertThat(row.orderJson().path("qty_closed").decimalValue()).isEqualByComparingTo("5");
        assertThat(row.orderJson().path("price").isNull()).isTrue();
    }

    /** A provider that reports the fill price leaves no pending window. */
    @Test
    void acceptedTrimWithPriceLeavesNoPendingMarker() {
        ExecutorPosition p = position(8L, "10", 0, "stop-old", null);
        when(gateway.flatten(eq("depot-1"), eq("SYNC"), any())).thenReturn(new CloseResult(
                new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("131"), "trim-ord-4",
                List.of(), false));

        service.execute(p, new BigDecimal("0.5"), "SOFT_TRIGGER", null, "soft-exit flatten",
                null, null, "run-1");

        verify(positionRepo, never()).markPendingTrim(anyLong(), any());
        assertThat(onlyRow().orderJson().path("price").decimalValue()).isEqualByComparingTo("131");
    }

    /** An EMPTY protective_legs list (a provider that does not restore legs) leaves the leg rows
     *  untouched — nothing replaced them. */
    @Test
    void acceptedTrimWithoutRestoredLegsLeavesTheLegRowsAlone() {
        ExecutorPosition p = position(9L, "10", 0, "stop-old", null);
        when(legRepo.findOpenByPosition(9L)).thenReturn(List.of(leg(90L, 9L, 1, "stop-old", "10")));
        when(gateway.flatten(eq("depot-1"), eq("SYNC"), any())).thenReturn(new CloseResult(
                new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("131"), "trim-ord-5",
                List.of(), false));

        service.execute(p, new BigDecimal("0.5"), "SOFT_TRIGGER", null, "soft-exit flatten",
                null, null, "run-1");

        verify(legRepo, never()).repointLegStop(anyLong(), any());
        verify(legRepo, never()).closeLeg(anyLong(), any(), any(), any());
    }

    /** P1 #2: a rejected partial close (LEG_RESTORE_FAILED_UNPROTECTED) repoints the leg rows the
     *  rollback re-issued, NULLS the unmatched one (reject-branch semantics), leaves trim_count
     *  alone and pages CRITICAL. */
    @Test
    void rejectedTrimRepointsOrNullsAndKeepsTheTrimCount() {
        ExecutorPosition p = position(10L, "4", 0, "stop-a", "stop-b");
        when(legRepo.findOpenByPosition(10L)).thenReturn(List.of(
                leg(100L, 10L, 1, "stop-a", "2"), leg(101L, 10L, 2, "stop-b", "2")));
        when(gateway.flatten(eq("depot-1"), eq("SYNC"), any())).thenThrow(new BrokerRejectedException(
                "rollback incomplete", "LEG_RESTORE_FAILED_UNPROTECTED",
                List.of(new RestoredLeg("stop-a", "stop-a3", new BigDecimal("2"), new BigDecimal("65")))));

        PartialExitService.Result r = service.execute(p, new BigDecimal("0.5"), "HARD_TRIGGER",
                "HARD_TARGET_HALF", "target-half flatten", null, null, "run-1");

        assertThat(r.outcome()).isEqualTo(PartialExitService.Outcome.REJECTED);
        verify(positionRepo).repointStopLegs(eq(10L), any());
        verify(legRepo).repointLegStop(100L, "stop-a3");
        verify(legRepo).repointLegStop(101L, null);
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt(), any(), anyBoolean());
        verify(telegram).notifyAlert(eq("SYNC"), eq("LEG_RESTORE_FAILED_UNPROTECTED"), eq("CRITICAL"), any());
        DecisionLog row = onlyRow();
        assertThat(row.action()).isEqualTo("ESCALATE");
        assertThat(row.reasonCode()).isEqualTo("BROKER_REJECTED");
        assertThat(row.reasoning()).startsWith("broker rejected target-half flatten [LEG_RESTORE_FAILED_UNPROTECTED]");
    }

    @Test
    void unavailableEscalatesWithoutTouchingTheBook() {
        ExecutorPosition p = position(11L, "10", 0, "stop-old", null);
        when(gateway.flatten(eq("depot-1"), eq("SYNC"), any()))
                .thenThrow(new BrokerUnavailableException("timeout"));

        PartialExitService.Result r = service.execute(p, new BigDecimal("0.5"), "HARD_TRIGGER",
                "HARD_TARGET_HALF", "target-half flatten", null, null, "run-1");

        assertThat(r.outcome()).isEqualTo(PartialExitService.Outcome.UNAVAILABLE);
        verify(positionRepo, never()).recordTrim(anyLong(), any(), anyInt(), any(), anyBoolean());
        assertThat(onlyRow().reasonCode()).isEqualTo("BROKER_UNAVAILABLE");
    }

    /** Ruling F3: a non-empty protective_legs list that matches NO open leg (stale/unknown ids)
     *  must not close every leg — the next reconcile's completeInterruptedClose would book the
     *  whole position CLOSED while the broker still holds the remainder. Rows stay, escalate. */
    @Test
    void acceptedTrimWhoseRestoredLegsMatchNoLegLeavesTheRowsAndEscalates() {
        ExecutorPosition p = position(12L, "4", 0, "stop-a", "stop-b");
        when(legRepo.findOpenByPosition(12L)).thenReturn(List.of(
                leg(120L, 12L, 1, "stop-a", "2"), leg(121L, 12L, 2, "stop-b", "2")));
        when(gateway.flatten(eq("depot-1"), eq("SYNC"), any())).thenReturn(new CloseResult(
                new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("131"), "trim-ord-6",
                List.of(new RestoredLeg("stop-unknown", "stop-x", new BigDecimal("2"), new BigDecimal("65"))),
                false));

        PartialExitService.Result r = service.execute(p, new BigDecimal("0.5"), "HARD_TRIGGER",
                "HARD_TARGET_HALF", "target-half flatten", null, null, "run-1");

        assertThat(r.trimmed()).isTrue();
        verify(positionRepo).recordTrim(eq(12L), eq(new BigDecimal("2")), eq(1), any(), eq(false));
        verify(legRepo, never()).repointLegStop(anyLong(), any());
        verify(legRepo, never()).closeLeg(anyLong(), any(), any(), any());
        ArgumentCaptor<DecisionLog> rows = ArgumentCaptor.forClass(DecisionLog.class);
        verify(decisionLogRepo, org.mockito.Mockito.times(2)).insert(rows.capture());
        assertThat(rows.getAllValues()).extracting(DecisionLog::action)
                .containsExactly("TRIM", "ESCALATE");
        DecisionLog escalation = rows.getAllValues().get(1);
        assertThat(escalation.reasonCode()).isEqualTo("TRIM_LEGS_UNMATCHED");
        assertThat(escalation.triggerType()).isEqualTo("HARD_TRIGGER");
        assertThat(escalation.symbol()).isEqualTo("SYNC");
        verify(telegram).notifyAlert(eq("SYNC"), eq("TRIM_LEGS_UNMATCHED"), eq("CRITICAL"), any());
    }
}
