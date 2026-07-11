package de.visterion.dracul.outcome;

import de.visterion.dracul.executor.DecisionLog;
import de.visterion.dracul.executor.DecisionLogRepository;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignal;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito unit test for {@link OutcomeBatchJob}'s pure business logic (quantity-weighted
 * realized R, MAE, and the counterfactual walk), independent of Spring/Testcontainers. Mirrors
 * the scenarios in {@code OutcomeBatchJobIT}, whose Testcontainers-backed Spring context could
 * not be exercised in this environment — see the Task 9 report for why (a pre-existing,
 * environment-level {@code @SpringBootTest} context-load failure reproducible on unmodified
 * branch HEAD, unrelated to this change).
 */
class OutcomeBatchJobTest {

    private final ExecutorPositionRepository positions = mock(ExecutorPositionRepository.class);
    private final DecisionLogRepository decisionLog = mock(DecisionLogRepository.class);
    private final ExecutorSignalRepository signals = mock(ExecutorSignalRepository.class);
    private final OutcomeLogRepository outcomeLog = mock(OutcomeLogRepository.class);
    private final AgoraMarketData marketData = mock(AgoraMarketData.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HypotheticalREngine engine = new HypotheticalREngine();

    private final OutcomeBatchJob job = new OutcomeBatchJob(
            positions, decisionLog, signals, outcomeLog, engine, marketData, mapper);

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private ExecutorPosition closedPosition(String symbol, String signalId, BigDecimal qty,
            BigDecimal exitPrice, BigDecimal realizedR, BigDecimal lowestPrice) {
        return closedPosition(7L, symbol, signalId, qty, exitPrice, realizedR, lowestPrice);
    }

    private ExecutorPosition closedPosition(long id, String symbol, String signalId, BigDecimal qty,
            BigDecimal exitPrice, BigDecimal realizedR, BigDecimal lowestPrice) {
        return new ExecutorPosition(
                id, "saxo-sim", symbol, "BUY", qty, bd("100"), bd("95"), bd("95"), 1, bd("5"),
                List.of(), signalId, "strigoi-spin", "2026-06-01 10:00:00.0", null, "CLOSED", null,
                bd("100"), bd("2.0"), 0, exitPrice, realizedR, "TAKE_PROFIT", "2026-06-10 10:00:00.0",
                null, null, null, null, null, 0, lowestPrice, null);
    }

    private DecisionLog decisionRow(String logId, String signalId, String symbol, String action,
            String reasonCode, tools.jackson.databind.JsonNode inputsSnapshot,
            tools.jackson.databind.JsonNode orderJson, String sourceAgent, String sourceAgentVersion) {
        return new DecisionLog(logId, "run-1", "exec-v0.2", "SIGNAL", signalId, sourceAgent,
                sourceAgentVersion, symbol, inputsSnapshot, null, action, reasonCode, orderJson,
                null, null, null, "2026-06-01 10:00:00.0");
    }

    /** NOTE: the TRIM/exit rows here carry NO {@code order_json.position_id} — this doubles as
     *  the fallback-path test: pre-linkage historical rows must still match via the
     *  symbol+calendar-day window heuristic. */
    @Test
    void tradeRecord_quantityWeightedRealizedR_matchesHandComputedFixture() {
        String symbol = "TRD1";
        String signalId = "sig-1";

        var enterOrderJson = mapper.createObjectNode();
        enterOrderJson.put("limit_price", bd("99"));
        DecisionLog enter = decisionRow("enter-log-1", signalId, symbol, "ENTER", null, null,
                enterOrderJson, "strigoi-spin", "v1");

        var trimOrderJson = mapper.createObjectNode();
        trimOrderJson.put("fraction", 0.33);
        trimOrderJson.put("qty_closed", bd("33"));
        trimOrderJson.put("qty_remaining", bd("67"));
        trimOrderJson.put("price", bd("110"));
        DecisionLog trim = decisionRow("trim-log-1", null, symbol, "TRIM", null, null,
                trimOrderJson, null, null);

        DecisionLog exit = decisionRow("exit-log-1", null, symbol, "EXIT_FULL", "TAKE_PROFIT",
                null, null, null, null);

        ExecutorPosition closed = closedPosition(symbol, signalId, bd("67"), bd("104"), bd("0.8"), bd("93"));

        when(positions.findClosed()).thenReturn(List.of(closed));
        when(decisionLog.findBySignalIdAndAction(signalId, "ENTER")).thenReturn(enter);
        when(outcomeLog.isComplete("enter-log-1")).thenReturn(false);
        when(decisionLog.findBySymbolAndActionsBetween(eq(symbol), eq(List.of("TRIM")), any(), any()))
                .thenReturn(List.of(trim));
        when(decisionLog.findBySymbolAndActionsBetween(
                eq(symbol), eq(List.of("EXIT_FULL", "LOG_HARD_EXIT", "RECONCILE_CLOSE")), any(), any()))
                .thenReturn(List.of(exit));
        when(decisionLog.findBySymbolAndActionsBetween(eq(symbol), eq(List.of("ENTER")), any(), any()))
                .thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of());

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog, times(1)).upsert(captor.capture());

        OutcomeLogRow row = captor.getValue();
        assertThat(row.kind()).isEqualTo("TRADE");
        assertThat(row.logIdRef()).isEqualTo("enter-log-1");
        assertThat(row.fillPrice()).isEqualByComparingTo("100");
        assertThat(row.slippageVsLimit()).isEqualByComparingTo("1"); // 100 - 99
        // weighted R = (33*2.0 + 67*0.8) / 100 = 1.196
        assertThat(row.realizedR()).isEqualByComparingTo("1.196");
        assertThat(row.maeR()).isEqualByComparingTo("-1.4"); // (93-100)/5
        assertThat(row.exitTrigger()).isEqualTo("TAKE_PROFIT");
        assertThat(row.complete()).isTrue();
        assertThat(row.sourceAgent()).isEqualTo("strigoi-spin");
        assertThat(row.ruleVersion()).isEqualTo("exec-v0.2");
    }

    /** Two lifecycles on the SAME symbol whose calendar-day windows overlap (same-day
     *  close+reentry, possible with cooldown-days=0): the window heuristic alone WOULD leak each
     *  lifecycle's TRIM/exit rows into the other's weighted-R math. {@code order_json.position_id}
     *  stamping must keep them apart — each outcome row uses only its own exits. */
    @Test
    void twoSameDayLifecyclesOnOneSymbol_positionIdKeepsTrimsAndExitsApart() {
        String symbol = "LEAK1";

        DecisionLog enterA = decisionRow("enter-A", "sig-A", symbol, "ENTER", null, null, null, null, null);
        DecisionLog enterB = decisionRow("enter-B", "sig-B", symbol, "ENTER", null, null, null, null, null);

        // Lifecycle A (position 1): trim 33 @ 110 (R=2.0), final 67 @ 104 (R=0.8) -> 1.196
        var trimAJson = mapper.createObjectNode();
        trimAJson.put("fraction", 0.33);
        trimAJson.put("qty_closed", bd("33"));
        trimAJson.put("price", bd("110"));
        trimAJson.put("position_id", 1L);
        DecisionLog trimA = decisionRow("trim-A", null, symbol, "TRIM", null, null, trimAJson, null, null);
        var exitAJson = mapper.createObjectNode();
        exitAJson.put("fraction", 1.0);
        exitAJson.put("position_id", 1L);
        DecisionLog exitA = decisionRow("exit-A", null, symbol, "EXIT_FULL", "TAKE_PROFIT",
                null, exitAJson, null, null);

        // Lifecycle B (position 2): trim 50 @ 120 (R=4.0), final 50 @ 100 (R=0) -> 2.0
        var trimBJson = mapper.createObjectNode();
        trimBJson.put("fraction", 0.5);
        trimBJson.put("qty_closed", bd("50"));
        trimBJson.put("price", bd("120"));
        trimBJson.put("position_id", 2L);
        DecisionLog trimB = decisionRow("trim-B", null, symbol, "TRIM", null, null, trimBJson, null, null);
        var exitBJson = mapper.createObjectNode();
        exitBJson.put("fraction", 1.0);
        exitBJson.put("position_id", 2L);
        DecisionLog exitB = decisionRow("exit-B", null, symbol, "LOG_HARD_EXIT", "HARD_STOP",
                null, exitBJson, null, null);

        ExecutorPosition posA = closedPosition(1L, symbol, "sig-A", bd("67"), bd("104"), bd("0.8"), null);
        ExecutorPosition posB = closedPosition(2L, symbol, "sig-B", bd("50"), bd("100"), bd("0"), null);

        when(positions.findClosed()).thenReturn(List.of(posA, posB));
        when(decisionLog.findBySignalIdAndAction("sig-A", "ENTER")).thenReturn(enterA);
        when(decisionLog.findBySignalIdAndAction("sig-B", "ENTER")).thenReturn(enterB);
        when(outcomeLog.isComplete("enter-A")).thenReturn(false);
        when(outcomeLog.isComplete("enter-B")).thenReturn(false);
        // The overlapping calendar-day window returns BOTH lifecycles' rows for BOTH positions —
        // exactly the leak scenario; position_id filtering must sort it out.
        when(decisionLog.findBySymbolAndActionsBetween(eq(symbol), eq(List.of("TRIM")), any(), any()))
                .thenReturn(List.of(trimA, trimB));
        when(decisionLog.findBySymbolAndActionsBetween(
                eq(symbol), eq(List.of("EXIT_FULL", "LOG_HARD_EXIT", "RECONCILE_CLOSE")), any(), any()))
                .thenReturn(List.of(exitA, exitB));
        when(decisionLog.findBySymbolAndActionsBetween(eq(symbol), eq(List.of("ENTER")), any(), any()))
                .thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of());

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog, times(2)).upsert(captor.capture());

        OutcomeLogRow rowA = captor.getAllValues().stream()
                .filter(r -> "enter-A".equals(r.logIdRef())).findFirst().orElseThrow();
        OutcomeLogRow rowB = captor.getAllValues().stream()
                .filter(r -> "enter-B".equals(r.logIdRef())).findFirst().orElseThrow();

        // A: (33*2.0 + 67*0.8)/100 = 1.196 — trimB's R=4.0 leg must NOT be in here
        assertThat(rowA.realizedR()).isEqualByComparingTo("1.196");
        assertThat(rowA.exitTrigger()).isEqualTo("TAKE_PROFIT");
        assertThat(rowA.exitLogId()).isEqualTo("exit-A");
        assertThat(rowA.partialExits().size()).isEqualTo(1);
        assertThat(rowA.partialExits().get(0).path("log_id").asString()).isEqualTo("trim-A");

        // B: (50*4.0 + 50*0)/100 = 2.0 — trimA's leg must NOT be in here
        assertThat(rowB.realizedR()).isEqualByComparingTo("2.0");
        assertThat(rowB.exitTrigger()).isEqualTo("HARD_STOP");
        assertThat(rowB.exitLogId()).isEqualTo("exit-B");
        assertThat(rowB.partialExits().size()).isEqualTo(1);
        assertThat(rowB.partialExits().get(0).path("log_id").asString()).isEqualTo("trim-B");
    }

    @Test
    void tradeRecord_alreadyComplete_isSkipped() {
        String symbol = "TRD2";
        DecisionLog enter = decisionRow("enter-log-2", "sig-2", symbol, "ENTER", null, null, null, null, null);
        ExecutorPosition closed = closedPosition(symbol, "sig-2", bd("10"), bd("105"), bd("1.0"), null);

        when(positions.findClosed()).thenReturn(List.of(closed));
        when(decisionLog.findBySignalIdAndAction("sig-2", "ENTER")).thenReturn(enter);
        when(outcomeLog.isComplete("enter-log-2")).thenReturn(true);
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of());

        job.run();

        verify(outcomeLog, times(0)).upsert(any());
    }

    @Test
    void counterfactual_walksBarsAndLabelsHunter() {
        String symbol = "CFT1";
        var inputsSnapshot = mapper.readTree("{\"order_price\":100,\"atr\":2}");
        DecisionLog reject = decisionRow("reject-log-1", "sig-3", symbol, "REJECT", "PACE_LIMIT",
                inputsSnapshot, null, "strigoi-spin", "v1");

        when(positions.findClosed()).thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of(reject));
        when(outcomeLog.isComplete("reject-log-1")).thenReturn(false);
        when(signals.findById("sig-3")).thenReturn(new ExecutorSignal("sig-3", "strigoi-spin", "v1",
                symbol, "BUY", 0.7, "SPINOFF", List.of(), "3m", bd("100"), "REJECTED", null));

        // 20 bars drifting from 100.5 to 110 -> r_after_20d = 2.0, label true (mirrors
        // HypotheticalREngineTest.driftUpTo110By20Days).
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<OhlcBar> bars = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            BigDecimal close = bd("100").add(bd("0.5").multiply(BigDecimal.valueOf(i)));
            bars.add(new OhlcBar(start.plusDays(i), close.add(bd("0.5")), close.add(bd("0.5")),
                    close.subtract(bd("0.5")), close, 1000L));
        }
        when(marketData.dailyOhlcHistory(anyString(), anyInt())).thenReturn(bars);

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog, times(1)).upsert(captor.capture());

        OutcomeLogRow row = captor.getValue();
        assertThat(row.kind()).isEqualTo("COUNTERFACTUAL");
        assertThat(row.reasonCode()).isEqualTo("PACE_LIMIT");
        assertThat(row.hypothetical().path("r_after_20d").asDouble()).isEqualTo(2.0);
        assertThat(row.hunterLabel()).isTrue();
        assertThat(row.complete()).isFalse(); // fewer than 60 bars
    }

    @Test
    void counterfactual_missingAtrOrderPrice_skippedAndComplete() {
        String symbol = "CFT2";
        DecisionLog reject = decisionRow("reject-log-2", null, symbol, "REJECT", "LOW_CONFIDENCE",
                null, null, "strigoi-spin", "v1");

        when(positions.findClosed()).thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of(reject));
        when(outcomeLog.isComplete("reject-log-2")).thenReturn(false);

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog, times(1)).upsert(captor.capture());

        OutcomeLogRow row = captor.getValue();
        assertThat(row.complete()).isTrue();
        assertThat(row.hypothetical().path("skipped_reason").isNull()).isFalse();
    }
}
