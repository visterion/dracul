package de.visterion.dracul.outcome;

import de.visterion.dracul.executor.DecisionLog;
import de.visterion.dracul.executor.DecisionLogRepository;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignal;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.marketdata.AgoraClient;
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
import static org.mockito.Mockito.never;
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

    private final de.visterion.dracul.executor.ExecutorDecisionRepository executorDecisions =
            mock(de.visterion.dracul.executor.ExecutorDecisionRepository.class);
    private final de.visterion.dracul.executor.RuleVersionProvider ruleVersions =
            mock(de.visterion.dracul.executor.RuleVersionProvider.class);

    private final OutcomeBatchJob job = new OutcomeBatchJob(
            positions, decisionLog, signals, outcomeLog, engine, marketData, mapper,
            executorDecisions, ruleVersions);

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private ExecutorPosition closedPosition(String symbol, String signalId, BigDecimal qty,
            BigDecimal exitPrice, BigDecimal realizedR, BigDecimal lowestPrice) {
        return closedPosition(7L, symbol, signalId, qty, exitPrice, realizedR, lowestPrice);
    }

    private ExecutorPosition closedPosition(long id, String symbol, String signalId, BigDecimal qty,
            BigDecimal exitPrice, BigDecimal realizedR, BigDecimal lowestPrice) {
        return new ExecutorPosition(
                id, "depot-1", symbol, "BUY", qty, bd("100"), bd("95"), bd("95"), 1, bd("5"),
                List.of(), signalId, "strigoi-spin", "2026-06-01 10:00:00.0", null, "CLOSED", null,
                bd("100"), bd("2.0"), 0, exitPrice, realizedR, "TAKE_PROFIT", "2026-06-10 10:00:00.0",
                null, null, null, null, null, 0, lowestPrice, null, null, null, null, null, false, null, null);
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

    /** Like {@link #closedPosition} but with caller-controlled entry/close dates, for the
     *  re-entry-window tests below. */
    private ExecutorPosition closedPositionOn(long id, String symbol, String signalId,
            LocalDate entryDate, LocalDate closedDate) {
        return new ExecutorPosition(
                id, "depot-1", symbol, "BUY", bd("10"), bd("100"), bd("95"), bd("95"), 1, bd("5"),
                List.of(), signalId, "strigoi-spin", entryDate.toString(), null, "CLOSED", null,
                bd("100"), bd("2.0"), 0, bd("105"), bd("1.0"), "TAKE_PROFIT", closedDate.toString(),
                null, null, null, null, null, 0, null, null, null, null, null, null, false, null, null);
    }

    /** reentry_within_10d can only fire on runs AFTER the close — so a TRADE row must stay
     *  complete=false inside the 14-calendar-day window (re-runs recompute the flag) and only
     *  flip complete once the window has elapsed. Marking it complete on the first run would
     *  freeze reentry at false forever. */
    @Test
    void tradeRecord_insideReentryWindow_staysIncomplete_thenCapturesReentryOnRerun() {
        String symbol = "REENT1";
        java.time.LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.LocalDate closedDate = today.minusDays(5); // inside the 14d window
        java.time.LocalDate entryDate = closedDate.minusDays(9);

        DecisionLog enter = decisionRow("enter-re", "sig-re", symbol, "ENTER", null, null, null,
                "strigoi-spin", "v1");
        DecisionLog newEnter = decisionRow("enter-re-2", "sig-re-2", symbol, "ENTER", null, null,
                null, "strigoi-spin", "v1");
        ExecutorPosition closed = closedPositionOn(9L, symbol, "sig-re", entryDate, closedDate);

        when(positions.findClosed()).thenReturn(List.of(closed));
        when(decisionLog.findBySignalIdAndAction("sig-re", "ENTER")).thenReturn(enter);
        when(outcomeLog.isComplete("enter-re")).thenReturn(false);
        when(decisionLog.findBySymbolAndActionsBetween(eq(symbol), eq(List.of("TRIM")), any(), any()))
                .thenReturn(List.of());
        when(decisionLog.findBySymbolAndActionsBetween(
                eq(symbol), eq(List.of("EXIT_FULL", "LOG_HARD_EXIT", "RECONCILE_CLOSE")), any(), any()))
                .thenReturn(List.of());
        // First run: no re-entry yet; second run: a fresh ENTER appeared within the window.
        when(decisionLog.findBySymbolAndActionsBetween(eq(symbol), eq(List.of("ENTER")), any(), any()))
                .thenReturn(List.of())
                .thenReturn(List.of(newEnter));
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of());

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog, times(1)).upsert(captor.capture());
        assertThat(captor.getValue().complete()).isFalse();
        assertThat(captor.getValue().reentryWithin10d()).isFalse();

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor2 = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog, times(2)).upsert(captor2.capture());
        OutcomeLogRow rerun = captor2.getAllValues().get(1);
        assertThat(rerun.reentryWithin10d()).isTrue();
        assertThat(rerun.complete()).isFalse(); // window still open — keep recomputing
    }

    @Test
    void tradeRecord_afterReentryWindow_marksComplete() {
        String symbol = "REENT2";
        java.time.LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.LocalDate closedDate = today.minusDays(14); // window fully elapsed
        java.time.LocalDate entryDate = closedDate.minusDays(9);

        DecisionLog enter = decisionRow("enter-re3", "sig-re3", symbol, "ENTER", null, null, null,
                "strigoi-spin", "v1");
        ExecutorPosition closed = closedPositionOn(10L, symbol, "sig-re3", entryDate, closedDate);

        when(positions.findClosed()).thenReturn(List.of(closed));
        when(decisionLog.findBySignalIdAndAction("sig-re3", "ENTER")).thenReturn(enter);
        when(outcomeLog.isComplete("enter-re3")).thenReturn(false);
        when(decisionLog.findBySymbolAndActionsBetween(eq(symbol), any(), any(), any()))
                .thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of());

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog, times(1)).upsert(captor.capture());
        assertThat(captor.getValue().complete()).isTrue();
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

    /** SP12: a "3m" horizon resolves to 64 trading days (resolveHorizon), which is MORE than the
     *  60-bar floor the completion check used to be fixed at. 61 flat bars (never touching either
     *  barrier) must NOT complete the row -- tripleBarrierLabel only answers "neither barrier hit"
     *  once bars.size() >= horizonTradingDays, so completing early would freeze hunter_label at
     *  NULL forever (isComplete's early return in OutcomeBatchJob skips a completed row). */
    @Test
    void reject_3mHorizon_61BarsNoTouch_staysIncomplete() {
        String symbol = "CFT3M";
        var inputsSnapshot = mapper.readTree("{\"order_price\":100,\"atr\":2}");
        DecisionLog reject = decisionRow("reject-log-3m", "sig-3m", symbol, "REJECT", "PACE_LIMIT",
                inputsSnapshot, null, "strigoi-spin", "v1");

        when(positions.findClosed()).thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of(reject));
        when(outcomeLog.isComplete("reject-log-3m")).thenReturn(false);
        when(signals.findById("sig-3m")).thenReturn(new ExecutorSignal("sig-3m", "strigoi-spin", "v1",
                symbol, "BUY", 0.7, "SPINOFF", List.of(), "3m", bd("100"), "REJECTED", null));

        // 61 flat bars at the entry price (100): stop = 100 - 2.5*2 = 95, target = 105, neither is
        // ever touched.
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<OhlcBar> bars = new ArrayList<>();
        for (int i = 1; i <= 61; i++) {
            bars.add(new OhlcBar(start.plusDays(i), bd("100"), bd("100"), bd("100"), bd("100"), 1000L));
        }
        when(marketData.dailyOhlcHistory(anyString(), anyInt())).thenReturn(bars);

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog, times(1)).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();
        assertThat(row.complete()).isFalse();
        assertThat(row.hunterLabel()).isNull();
    }

    /** SP4: OutcomeBatchJob is deliberately NOT changed. A SIGNAL/REJECT row carrying STALE_FILL
     *  walks into a COUNTERFACTUAL exactly like every other reject reason — the exclusion lives in
     *  OutcomeLogRepository.findVetoRows and nowhere else. */
    @Test
    void counterfactual_staleFillRejectStillProducesACounterfactualRow() {
        String symbol = "CFT9";
        var inputsSnapshot = mapper.readTree("{\"order_price\":100,\"atr\":2}");
        DecisionLog reject = decisionRow("reject-log-9", "sig-9", symbol, "REJECT", "STALE_FILL",
                inputsSnapshot, null, "strigoi-spin", "v1");

        when(positions.findClosed()).thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of(reject));
        when(outcomeLog.isComplete("reject-log-9")).thenReturn(false);
        when(signals.findById("sig-9")).thenReturn(new ExecutorSignal("sig-9", "strigoi-spin", "v1",
                symbol, "BUY", 0.7, "SPINOFF", List.of(), "3m", bd("100"), "REJECTED", null));

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
        assertThat(captor.getValue().kind()).isEqualTo("COUNTERFACTUAL");
        assertThat(captor.getValue().reasonCode()).isEqualTo("STALE_FILL");
        assertThat(captor.getValue().hunterLabel()).isTrue();
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

    // =========================================================================
    // Agora "nothing to serve" payload (available:false, no bars).
    //
    // NOT_FOUND is no longer collapsed into an error envelope upstream, so this
    // arrives as a normal get_ohlc payload instead of a MarketDataException. These
    // tests drive the REAL AgoraMarketData parser over a stubbed AgoraClient so the
    // post-deploy path is what is exercised.
    // =========================================================================

    private OutcomeBatchJob jobOverAgora(String ohlcPayload) {
        AgoraClient client = mock(AgoraClient.class);
        when(client.callTool(eq("get_ohlc"), any()))
                .thenReturn(mapper.readTree(ohlcPayload));
        return new OutcomeBatchJob(positions, decisionLog, signals, outcomeLog, engine,
                new AgoraMarketData(client), mapper, executorDecisions, ruleVersions);
    }

    private DecisionLog rejectFor(String symbol, String logId, String signalId) {
        return decisionRow(logId, signalId, symbol, "REJECT", "PACE_LIMIT",
                mapper.readTree("{\"order_price\":100,\"atr\":2}"), null, "strigoi-spin", "v1");
    }

    private void wireReject(DecisionLog reject, String signalId) {
        when(positions.findClosed()).thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of(reject));
        when(outcomeLog.isComplete(reject.logId())).thenReturn(false);
        when(signals.findById(signalId)).thenReturn(new ExecutorSignal(signalId, "strigoi-spin", "v1",
                reject.symbol(), "BUY", 0.7, "SPINOFF", List.of(), "3m", bd("100"), "REJECTED", null));
    }

    @Test
    void counterfactual_unavailableOhlcPayload_writesSkippedNotStoppedOutFalse() {
        DecisionLog reject = rejectFor("SYNA", "reject-nodata", "sig-nd");
        wireReject(reject, "sig-nd");

        jobOverAgora("{\"symbol\":\"SYNA\",\"available\":false,\"bars\":[]}").run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog, times(1)).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();

        assertThat(row.kind()).isEqualTo("COUNTERFACTUAL");
        // The defect this guards: a symbol with no data must not answer "the stop was
        // never hit". findVetoRows() reads this column with no complete-filter.
        assertThat(row.hypothetical().path("would_have_stopped_out").isNull())
                .as("would_have_stopped_out must be null (not evaluated), never a fabricated false")
                .isTrue();
        // Visibility: the row is explicitly marked skipped, which is what
        // CalibrationService counts separately and excludes from every veto mean.
        assertThat(row.hypothetical().path("skipped_reason").asString())
                .contains("no OHLC bars available");
        assertThat(row.hypothetical().path("r_after_20d").isNull()).isTrue();
        assertThat(row.hypothetical().path("r_after_60d").isNull()).isTrue();
        assertThat(row.hunterLabel()).isNull();
        // A data blackout is not a verdict: the row stays open so a later run recomputes it.
        assertThat(row.complete()).isFalse();
    }

    @Test
    void counterfactual_healthyOhlcPayload_stillEvaluatesTheWalk() {
        DecisionLog reject = rejectFor("SYNB", "reject-ok", "sig-ok");
        wireReject(reject, "sig-ok");

        // The decision row is dated 2026-06-01; bars after it drift 100.5 -> 110, so the
        // +1R target (entry 100, atr 2 -> stop 96, rPerShare 4) is reached and the stop is not.
        StringBuilder bars = new StringBuilder("[");
        LocalDate start = LocalDate.of(2026, 6, 1);
        for (int i = 1; i <= 20; i++) {
            BigDecimal close = bd("100").add(bd("0.5").multiply(BigDecimal.valueOf(i)));
            if (i > 1) bars.append(',');
            bars.append("{\"date\":\"").append(start.plusDays(i)).append("\",")
                .append("\"open\":").append(close)
                .append(",\"high\":").append(close.add(bd("0.5")))
                .append(",\"low\":").append(close.subtract(bd("0.5")))
                .append(",\"close\":").append(close)
                .append(",\"volume\":1000}");
        }
        bars.append(']');

        jobOverAgora("{\"symbol\":\"SYNB\",\"available\":true,\"bars\":" + bars + "}").run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog, times(1)).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();

        assertThat(row.hypothetical().path("skipped_reason").isNull()).isTrue();
        assertThat(row.hypothetical().path("would_have_stopped_out").asBoolean()).isFalse();
        assertThat(row.hypothetical().path("r_after_20d").asDouble()).isEqualTo(2.0);
        assertThat(row.hunterLabel()).isTrue();
    }

    // =========================================================================
    // LLM_SKIP — counterfactual for signals the LLM skipped outright (no place_entry, no
    // decision_log row). Walked from the persisted reference_bar_date/reference_atr instead of
    // an inputs_snapshot, keyed on log_id_ref = "skip:" + signal_id.
    // =========================================================================

    private de.visterion.dracul.executor.ExecutorDecision skipDecision(String signalId, String symbol) {
        return new de.visterion.dracul.executor.ExecutorDecision(1L, signalId, symbol, false, null,
                List.of(), "LLM chose to skip", null, "run-1", "2026-09-05 21:00:00.0", "SKIP");
    }

    private ExecutorSignal skippedSignal(String signalId, String symbol) {
        return new ExecutorSignal(signalId, "strigoi-spin", "v1", symbol, "BUY", 0.7, "SPINOFF",
                List.of(), "3m", bd("100"), "SKIPPED", null, null, null,
                LocalDate.parse("2026-09-04"), bd("2"));
    }

    private void wireSkip(String signalId, String symbol, ExecutorSignal signal, List<OhlcBar> bars) {
        when(positions.findClosed()).thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of());
        when(executorDecisions.findSkipsWithoutDecisionLog())
                .thenReturn(List.of(skipDecision(signalId, symbol)));
        when(signals.findById(signalId)).thenReturn(signal);
        when(outcomeLog.isComplete("skip:" + signalId)).thenReturn(false);
        when(ruleVersions.active()).thenReturn("exec-v0.6");
        when(marketData.dailyOhlcHistory(eq(symbol), anyInt())).thenReturn(bars);
    }

    /** Like {@link #wireSkip}, but the decision row's symbol disagrees with the signal's own
     *  symbol -- the 2026-09-11 prod defect where a crossed signal_id/symbol pair persisted the
     *  wrong instrument on the decision row. Bars are stubbed ONLY for the SIGNAL's symbol: the
     *  batch must walk that one, not the decision row's. */
    private void wireSkipMismatchedSymbol(String signalId, String decisionSymbol,
            ExecutorSignal signal, List<OhlcBar> bars) {
        when(positions.findClosed()).thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of());
        when(executorDecisions.findSkipsWithoutDecisionLog())
                .thenReturn(List.of(skipDecision(signalId, decisionSymbol)));
        when(signals.findById(signalId)).thenReturn(signal);
        when(outcomeLog.isComplete("skip:" + signalId)).thenReturn(false);
        when(ruleVersions.active()).thenReturn("exec-v0.6");
        when(marketData.dailyOhlcHistory(eq(signal.symbol()), anyInt())).thenReturn(bars);
    }

    /** Rising series starting the day AFTER the anchor bar. */
    private static List<OhlcBar> risingBarsFrom(LocalDate anchor, int n) {
        List<OhlcBar> bars = new ArrayList<>();
        // One bar ON the anchor date, which the walk must EXCLUDE (fetchBarsAfter is strict).
        bars.add(new OhlcBar(anchor, bd("100"), bd("100"), bd("100"), bd("100"), 1000L));
        for (int i = 1; i <= n; i++) {
            BigDecimal px = bd("100").add(bd("1").multiply(BigDecimal.valueOf(i)));
            bars.add(new OhlcBar(anchor.plusDays(i), px, px, px, px, 1000L));
        }
        return bars;
    }

    /** Like {@link #risingBarsFrom}, but the FIRST bar after the anchor gaps DOWN to an open of 99
     *  and closes at 104, so the open and the close of that bar are far apart and the entry the
     *  implementation picks is observable in the R. Bar i >= 2 is flat at 104 + (i - 1). */
    private static List<OhlcBar> gapOpenBarsFrom(LocalDate anchor, int n) {
        List<OhlcBar> bars = new ArrayList<>();
        bars.add(new OhlcBar(anchor, bd("100"), bd("100"), bd("100"), bd("100"), 1000L));
        bars.add(new OhlcBar(anchor.plusDays(1), bd("99"), bd("104"), bd("99"), bd("104"), 1000L));
        for (int i = 2; i <= n; i++) {
            BigDecimal px = bd("104").add(BigDecimal.valueOf(i - 1));
            bars.add(new OhlcBar(anchor.plusDays(i), px, px, px, px, 1000L));
        }
        return bars;
    }

    /** Like {@link #risingBarsFrom}, but the FIRST bar after the anchor has a non-positive open
     *  (a provider that emits {@code BigDecimal.ZERO} for a missing open) while every other field
     *  on that bar, and every later bar, is valid. Closes for the first 20 walked bars ramp
     *  101..120, matching {@link #risingBarsFrom}'s ramp, so entry_source is the only thing that
     *  moves. */
    private static List<OhlcBar> zeroOpenBarsFrom(LocalDate anchor, int n) {
        List<OhlcBar> bars = new ArrayList<>();
        bars.add(new OhlcBar(anchor, bd("100"), bd("100"), bd("100"), bd("100"), 1000L));
        bars.add(new OhlcBar(anchor.plusDays(1), bd("0"), bd("101"), bd("101"), bd("101"), 1000L));
        for (int i = 2; i <= n; i++) {
            BigDecimal px = bd("100").add(BigDecimal.valueOf(i));
            bars.add(new OhlcBar(anchor.plusDays(i), px, px, px, px, 1000L));
        }
        return bars;
    }

    /** Like {@link #risingBarsFrom}, but the ANCHOR bar dips to a low of 90 -- below any stop this
     *  fixture can produce. If the anchor bar were ever walked, would_have_stopped_out would flip
     *  to true; fetchBarsAfter's strict date filter is what keeps it false. */
    private static List<OhlcBar> anchorBarDipsFrom(LocalDate anchor, int n) {
        List<OhlcBar> bars = new ArrayList<>();
        bars.add(new OhlcBar(anchor, bd("100"), bd("100"), bd("90"), bd("100"), 1000L));
        for (int i = 1; i <= n; i++) {
            BigDecimal px = bd("100").add(BigDecimal.valueOf(i));
            bars.add(new OhlcBar(anchor.plusDays(i), px, px, px, px, 1000L));
        }
        return bars;
    }

    @Test
    void llmSkip_writesCounterfactualKeyedOnTheSignal() {
        String signalId = "sig-skip-1";
        wireSkip(signalId, "SKIPCO", skippedSignal(signalId, "SKIPCO"),
                risingBarsFrom(LocalDate.parse("2026-09-04"), 70));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();
        // Keyed on the SIGNAL: a re-sent submit_decision inserting a second SKIP row for the same
        // signal must upsert this row, not add one. The "skip:" prefix keeps it disjoint from
        // decision_log.log_id UUIDs in the TEXT UNIQUE column.
        assertThat(row.logIdRef()).isEqualTo("skip:" + signalId);
        assertThat(row.kind()).isEqualTo("COUNTERFACTUAL");
        assertThat(row.reasonCode()).isEqualTo("LLM_SKIP");
        assertThat(row.symbol()).isEqualTo("SKIPCO");
        assertThat(row.sourceAgent()).isEqualTo("strigoi-spin");
        assertThat(row.agentVersion()).isEqualTo("v1");
        assertThat(row.ruleVersion()).isEqualTo("exec-v0.6");
        // SP8: the counterfactual enters at the OPEN OF THE FIRST WALKED BAR -- the first price
        // reachable after the emission -- not at reference_price (which stays the live print at
        // emission and is what the drift vetoes compare against). The walk starts at the bar
        // AFTER reference_bar_date (2026-09-04), so the anchor-date bar (price 100) is excluded
        // and after[0] is the 09-05 bar with open 101.
        //   entry      = after[0].open()                    = 101
        //   stop       = deriveStopAnchor(BUY, 101, atr 2, null) = 101 - 2.5*2 = 96
        //   rPerShare  = |101 - 96|                          = 5
        //   after[19]  = the 20th walked bar, price 100 + 20 = 120 (no low ever reaches 96)
        //   r_after_20d = (120 - 101) / 5                    = 3.8
        // The value discriminates all three candidate implementations: entry+stop from the next
        // open -> 3.8; entry from the next open with the stop still from reference_price 100 ->
        // (120-101)/6 = 3.1667; both from reference_price -> (120-100)/5 = 4.0 (pre-SP8).
        assertThat(row.hypothetical().path("r_after_20d").asDouble()).isEqualTo(3.8);
        assertThat(row.hypothetical().path("entry_source").asString()).isEqualTo("next_bar_open");
        assertThat(row.hypothetical().path("entry_price").asDouble()).isEqualTo(101.0);
        assertThat(row.hypothetical().path("skipped_reason").isNull()).isTrue();
        assertThat(row.complete()).isTrue();
    }

    /** Like {@link #skippedSignal}, but with a caller-chosen horizon string. */
    private ExecutorSignal skippedSignalWithHorizon(String signalId, String symbol, String horizon) {
        return new ExecutorSignal(signalId, "strigoi-spin", "v1", symbol, "BUY", 0.7, "SPINOFF",
                List.of(), horizon, bd("100"), "SKIPPED", null, null, null,
                LocalDate.parse("2026-09-04"), bd("2"));
    }

    /** {@code n} flat bars after the anchor, all OHLC pinned to {@code price} -- entry (the first
     *  bar's open) equals stop-anchor and target forever, so neither barrier of the triple-barrier
     *  walk is ever touched. */
    private static List<OhlcBar> flatBarsFrom(LocalDate anchor, BigDecimal price, int n) {
        List<OhlcBar> bars = new ArrayList<>();
        bars.add(new OhlcBar(anchor, price, price, price, price, 1000L)); // anchor bar, excluded
        for (int i = 1; i <= n; i++) {
            bars.add(new OhlcBar(anchor.plusDays(i), price, price, price, price, 1000L));
        }
        return bars;
    }

    /** SP12: "3m" resolves to 64 trading days (resolveHorizon). 61 never-touching bars is short of
     *  that horizon, so tripleBarrierLabel is still undecided (null) -- the row must stay
     *  incomplete, not freeze a null hunter_label under the old fixed 60-bar completion check. */
    @Test
    void llmSkip_3mHorizon_61BarsNoTouch_staysIncomplete() {
        String signalId = "sig-skip-3m-61";
        wireSkip(signalId, "SKIP3M61", skippedSignalWithHorizon(signalId, "SKIP3M61", "3m"),
                flatBarsFrom(LocalDate.parse("2026-09-04"), bd("100"), 61));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();
        assertThat(row.complete()).isFalse();
        assertThat(row.hunterLabel()).isNull();
    }

    /** Same fixture as above, but with the 64th bar reached -- the label horizon is now filled,
     *  tripleBarrierLabel answers "neither barrier hit" (false), and the row completes. */
    @Test
    void llmSkip_3mHorizon_64BarsNoTouch_completesWithFalseLabel() {
        String signalId = "sig-skip-3m-64";
        wireSkip(signalId, "SKIP3M64", skippedSignalWithHorizon(signalId, "SKIP3M64", "3m"),
                flatBarsFrom(LocalDate.parse("2026-09-04"), bd("100"), 64));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();
        assertThat(row.complete()).isTrue();
        assertThat(row.hunterLabel()).isFalse();
    }

    /** Regression guard: a short horizon ("1m" -> ~21 trading days) must still complete at 60
     *  bars, exactly as the old fixed-60 check did -- Math.max(60, horizon) keeps the floor. */
    @Test
    void llmSkip_1mHorizon_60BarsCompletes() {
        String signalId = "sig-skip-1m-60";
        wireSkip(signalId, "SKIP1M60", skippedSignalWithHorizon(signalId, "SKIP1M60", "1m"),
                flatBarsFrom(LocalDate.parse("2026-09-04"), bd("100"), 60));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();
        assertThat(row.complete()).isTrue();
        assertThat(row.hunterLabel()).isFalse();
    }

    /** Prod, 2026-09-11: a crossed signal_id/symbol pair in a submitted SKIP persisted the wrong
     *  instrument's symbol on the decision row. The batch must walk the SIGNAL's symbol, not the
     *  decision row's, so the outcome row heals on the next run. */
    @Test
    void llmSkip_walksTheSignalsSymbolWhenTheDecisionRowDisagrees() {
        String signalId = "sig-skip-mismatch";
        wireSkipMismatchedSymbol(signalId, "WRONGCO", skippedSignal(signalId, "RIGHTCO"),
                risingBarsFrom(LocalDate.parse("2026-09-04"), 70));

        job.run();

        verify(marketData, never()).dailyOhlcHistory(eq("WRONGCO"), anyInt());

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();
        assertThat(row.symbol()).isEqualTo("RIGHTCO");
        // Same ramp as llmSkip_writesCounterfactualKeyedOnTheSignal: entry = after[0].open() = 101.
        assertThat(row.hypothetical().path("entry_price").asDouble()).isEqualTo(101.0);
    }

    @Test
    void llmSkip_walksFromReferenceBarDate_notADifferentAnchor() {
        // Same fixture as llmSkip_writesCounterfactualKeyedOnTheSignal, but reference_bar_date is
        // shifted ONE DAY LATER. fetchBarsAfter's filter is strict (date > anchor), so the bar
        // dated on the (now excluded) old anchor day is dropped too and the walk starts at the
        // 09-06 bar: entry = 102, stop = 102 - 2.5*2 = 97, rPerShare = 5, after[19] = the bar
        // priced 121, r_after_20d = (121 - 102) / 5 = 3.8 -- the SAME R as the un-shifted case,
        // because the SP8 entry moves with the anchor. The anchor is therefore detected through
        // entry_price (102 here vs 101 there), not through the R. If processSignalAnchored ever
        // anchored on something other than signal.referenceBarDate() (e.g. the
        // executor_decision's created_at), this fixture would silently keep producing 101.
        String signalId = "sig-skip-anchor";
        LocalDate trueAnchor = LocalDate.parse("2026-09-04");
        ExecutorSignal shiftedAnchor = new ExecutorSignal(signalId, "strigoi-spin", "v1",
                "ANCHORCO", "BUY", 0.7, "SPINOFF", List.of(), "3m", bd("100"), "SKIPPED", null,
                null, null, trueAnchor.plusDays(1), bd("2"));
        wireSkip(signalId, "ANCHORCO", shiftedAnchor, risingBarsFrom(trueAnchor, 70));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        assertThat(captor.getValue().hypothetical().path("entry_price").asDouble()).isEqualTo(102.0);
        assertThat(captor.getValue().hypothetical().path("r_after_20d").asDouble()).isEqualTo(3.8);
    }

    @Test
    void llmSkip_nullReferencePrice_writesSkippedReason() {
        String signalId = "sig-skip-2";
        ExecutorSignal noPrice = new ExecutorSignal(signalId, "strigoi-spin", "v1", "NOPXCO", "BUY",
                0.7, "SPINOFF", List.of(), "3m", null, "SKIPPED", null, null, null,
                LocalDate.parse("2026-09-04"), bd("2"));
        wireSkip(signalId, "NOPXCO", noPrice, risingBarsFrom(LocalDate.parse("2026-09-04"), 70));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        assertThat(captor.getValue().hypothetical().path("skipped_reason").isNull()).isFalse();
        assertThat(captor.getValue().hypothetical().path("would_have_stopped_out").isNull()).isTrue();
        assertThat(captor.getValue().complete()).isTrue();
    }

    @Test
    void llmSkip_twoDecisionRowsForOneSignal_produceOneOutcomeRow() {
        String signalId = "sig-skip-3";
        when(positions.findClosed()).thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of());
        when(executorDecisions.findSkipsWithoutDecisionLog()).thenReturn(List.of(
                skipDecision(signalId, "DUPCO"), skipDecision(signalId, "DUPCO")));
        when(signals.findById(signalId)).thenReturn(skippedSignal(signalId, "DUPCO"));
        when(outcomeLog.isComplete("skip:" + signalId)).thenReturn(false);
        when(ruleVersions.active()).thenReturn("exec-v0.6");
        when(marketData.dailyOhlcHistory(eq("DUPCO"), anyInt()))
                .thenReturn(risingBarsFrom(LocalDate.parse("2026-09-04"), 70));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog, times(2)).upsert(captor.capture());
        assertThat(captor.getAllValues()).extracting(OutcomeLogRow::logIdRef)
                .containsOnly("skip:" + signalId);
    }

    @Test
    void llmSkip_marketDataOutage_leavesTheRowForTheNextNight() {
        String signalId = "sig-skip-4";
        wireSkip(signalId, "OUTCO", skippedSignal(signalId, "OUTCO"), List.of());
        when(marketData.dailyOhlcHistory(eq("OUTCO"), anyInt()))
                .thenThrow(new de.visterion.dracul.marketdata.MarketDataException(
                        de.visterion.dracul.marketdata.MarketDataException.Kind.UNAVAILABLE, "provider down"));

        job.run();

        verify(outcomeLog, org.mockito.Mockito.never()).upsert(any());
    }

    @Test
    void llmSkip_oneBadRowDoesNotAbortTheLoop() {
        when(positions.findClosed()).thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of());
        when(executorDecisions.findSkipsWithoutDecisionLog()).thenReturn(List.of(
                skipDecision("sig-bad", "BADCO"), skipDecision("sig-ok", "OKCO")));
        when(signals.findById("sig-bad")).thenThrow(new IllegalStateException("synthetic failure"));
        when(signals.findById("sig-ok")).thenReturn(skippedSignal("sig-ok", "OKCO"));
        when(outcomeLog.isComplete(anyString())).thenReturn(false);
        when(ruleVersions.active()).thenReturn("exec-v0.6");
        when(marketData.dailyOhlcHistory(eq("OKCO"), anyInt()))
                .thenReturn(risingBarsFrom(LocalDate.parse("2026-09-04"), 70));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        assertThat(captor.getValue().logIdRef()).isEqualTo("skip:sig-ok");
    }

    // =========================================================================
    // SP2b — the ACCEPTED guard and the SIGNAL_EXPIRED_UNEVALUATED loop.
    // =========================================================================

    private de.visterion.dracul.executor.ExecutorDecision sweepDecision(String signalId, String symbol) {
        return new de.visterion.dracul.executor.ExecutorDecision(2L, signalId, symbol, false,
                "SIGNAL_EXPIRED", List.of("SIGNAL_EXPIRED:FAIL (6 > 5 days)"),
                de.visterion.dracul.executor.PendingSignalSweeper.RATIONALE_PREFIX
                        + "6 > 5 trading days",
                null, "run-1", "2026-09-16 05:00:00.0",
                de.visterion.dracul.executor.PendingSignalSweeper.ACTION);
    }

    private ExecutorSignal sweptSignal(String signalId, String symbol, String status,
            LocalDate anchor) {
        return new ExecutorSignal(signalId, "strigoi-spin", "v1", symbol, "BUY", 0.7, "SPINOFF",
                List.of(), "3m", bd("100"), status, null, null, null, anchor, bd("2"));
    }

    private void wireSwept(String signalId, String symbol, ExecutorSignal signal, List<OhlcBar> bars) {
        when(positions.findClosed()).thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of());
        when(executorDecisions.findSkipsWithoutDecisionLog()).thenReturn(List.of());
        when(executorDecisions.findSweptWithoutDecisionLog())
                .thenReturn(List.of(sweepDecision(signalId, symbol)));
        when(signals.findById(signalId)).thenReturn(signal);
        when(outcomeLog.isComplete("expired:" + signalId)).thenReturn(false);
        when(ruleVersions.active()).thenReturn("exec-v0.6");
        when(marketData.dailyOhlcHistory(eq(symbol), anyInt())).thenReturn(bars);
    }

    /** (a) I2: unlike {@code processSignalAnchored}, {@code processReject} has NO ACCEPTED guard
     *  — a decision_log REJECT row whose signal later reached ACCEPTED is still walked and
     *  upserted exactly as any other REJECT row. This is deliberate: {@code findVetoRows}' own
     *  read-side filter already excludes ACCEPTED-signal rows from {@code veto_precision}, while
     *  {@code findHunterBrierPoints} needs this very row — a TRADE row carries no
     *  {@code hunter_label}, so the REJECT counterfactual is the hunter Brier's only source for an
     *  entered signal. */
    @Test
    void processReject_forAnAcceptedSignal_isStillWalkedAndUpserted() {
        DecisionLog reject = rejectFor("ACCA", "reject-acc", "sig-acc");
        wireReject(reject, "sig-acc");
        when(signals.findById("sig-acc")).thenReturn(new ExecutorSignal("sig-acc", "strigoi-spin",
                "v1", "ACCA", "BUY", 0.7, "SPINOFF", List.of(), "3m", bd("100"), "ACCEPTED", null));
        when(marketData.dailyOhlcHistory(anyString(), anyInt()))
                .thenReturn(risingBarsFrom(LocalDate.parse("2026-06-01"), 70));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();
        assertThat(row.kind()).isEqualTo("COUNTERFACTUAL");
        assertThat(row.logIdRef()).isEqualTo("reject-acc");
        assertThat(row.symbol()).isEqualTo("ACCA");
        assertThat(row.hypothetical().path("skipped_reason").isNull()).isTrue();
        verify(marketData).dailyOhlcHistory(eq("ACCA"), anyInt());
    }

    /** (a2) The same guard on the signal-anchored path: the sweep marked REJECTED while an
     *  in-flight place_entry booked the position (status ACCEPTED). The SWEEP row still satisfies
     *  the finder, and this guard is what keeps a hypothetical away from the real trade. */
    @Test
    void processSignalAnchored_forAnAcceptedSignal_fetchesNothingAndWritesNothing() {
        String signalId = "sig-swept-acc";
        wireSwept(signalId, "ACCB",
                sweptSignal(signalId, "ACCB", "ACCEPTED", LocalDate.parse("2026-09-04")),
                risingBarsFrom(LocalDate.parse("2026-09-04"), 70));

        job.run();

        verify(outcomeLog, org.mockito.Mockito.never()).upsert(any());
        verify(marketData, org.mockito.Mockito.never()).dailyOhlcHistory(anyString(), anyInt());
    }

    /** (b) The swept loop writes its own reason code, keyed expired:<signal_id>, walked from the
     *  signal anchors. With only 6 bars after the anchor the 20-day window is not filled, so
     *  r_after_20d is null WITHOUT a skipped_reason and the row stays incomplete — exactly the
     *  shape the post-deploy verification expects after the first nightly batch. */
    @Test
    void sweptSignal_writesAnEmissionAnchoredCounterfactualUnderItsOwnReasonCode() {
        String signalId = "sig-swept-1";
        LocalDate anchor = LocalDate.parse("2026-09-04");
        wireSwept(signalId, "SWEEPCO", sweptSignal(signalId, "SWEEPCO", "REJECTED", anchor),
                risingBarsFrom(anchor, 6));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();

        assertThat(row.kind()).isEqualTo("COUNTERFACTUAL");
        assertThat(row.logIdRef()).isEqualTo("expired:" + signalId);
        assertThat(row.reasonCode()).isEqualTo("SIGNAL_EXPIRED_UNEVALUATED");
        assertThat(row.symbol()).isEqualTo("SWEEPCO");
        assertThat(row.sourceAgent()).isEqualTo("strigoi-spin");
        assertThat(row.agentVersion()).isEqualTo("v1");
        assertThat(row.ruleVersion()).isEqualTo("exec-v0.6");
        assertThat(row.hypothetical().path("r_after_20d").isNull()).isTrue();
        assertThat(row.hypothetical().path("skipped_reason").isNull()).isTrue();
        assertThat(row.complete()).isFalse();
    }

    /** (c) Regression guard for the generalisation: an LLM_SKIP row must still be written with the
     *  same key, the same reason code, the same entry convention and the same exact R as the
     *  dedicated skip-path test above. The asserted R moved from 4.0 to 3.8 with SP8 (entry is the
     *  101 open of the first walked bar, not the 100 reference price) -- the claim this test makes
     *  is "both paths agree", not "the number never changes". */
    @Test
    void llmSkip_isUnchangedByTheGeneralisation() {
        String signalId = "sig-skip-regress";
        wireSkip(signalId, "SKIPCO", skippedSignal(signalId, "SKIPCO"),
                risingBarsFrom(LocalDate.parse("2026-09-04"), 70));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();
        assertThat(row.logIdRef()).isEqualTo("skip:" + signalId);
        assertThat(row.reasonCode()).isEqualTo("LLM_SKIP");
        assertThat(row.hypothetical().path("r_after_20d").asDouble()).isEqualTo(3.8);
        assertThat(row.hypothetical().path("entry_source").asString()).isEqualTo("next_bar_open");
        assertThat(row.hypothetical().path("entry_price").asDouble()).isEqualTo(101.0);
        assertThat(row.complete()).isTrue();
    }

    /** SP8: the entry is the first walked bar's OPEN, not its close and not reference_price.
     *  Fixture: anchor bar 100, then a bar that opens at 99 and closes at 104, then 105, 106, ...
     *    entry       = 99                  (the open, not the 104 close, not the 100 reference)
     *    stop        = 99 - 2.5*2          = 94
     *    rPerShare   = 5
     *    after[19]   = the bar priced 104 + 19 = 123   (no low ever reaches 94)
     *    r_after_20d = (123 - 99) / 5      = 4.8
     *  Entry from reference_price 100 would give (123 - 100) / 5 = 4.6, and the next open with a
     *  reference-price stop would give (123 - 99) / 4 = 6.0 -- three distinct values. */
    @Test
    void signalAnchoredEntryIsTheFirstWalkedBarsOpen() {
        String signalId = "sig-skip-gap";
        wireSkip(signalId, "GAPCO", skippedSignal(signalId, "GAPCO"),
                gapOpenBarsFrom(LocalDate.parse("2026-09-04"), 70));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();
        assertThat(row.hypothetical().path("entry_source").asString()).isEqualTo("next_bar_open");
        assertThat(row.hypothetical().path("entry_price").asDouble()).isEqualTo(99.0);
        assertThat(row.hypothetical().path("r_after_20d").asDouble()).isEqualTo(4.8);
    }

    /** A signal too fresh to have a bar after its anchor: the walk still runs (with an empty bar
     *  list) and must say so honestly -- entry_source = reference_price, entry_price = the
     *  reference price. The next night, once a bar exists, the same row is re-walked under
     *  next_bar_open. This is NOT a data blackout: the source served bars, just none after the
     *  anchor, so the row stays incomplete and retryable rather than skipped. */
    @Test
    void signalAnchoredWithNoBarsYetWritesReferencePriceAsEntrySource() {
        String signalId = "sig-skip-fresh";
        wireSkip(signalId, "FRESHCO", skippedSignal(signalId, "FRESHCO"),
                risingBarsFrom(LocalDate.parse("2026-09-04"), 0));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();
        assertThat(row.hypothetical().path("entry_source").asString()).isEqualTo("reference_price");
        assertThat(row.hypothetical().path("entry_price").asDouble()).isEqualTo(100.0);
        assertThat(row.hypothetical().path("r_after_20d").isNull()).isTrue();
        assertThat(row.hypothetical().path("skipped_reason").isNull()).isTrue();
        assertThat(row.complete()).isFalse();
    }

    /** A first-after bar with a non-positive open (some providers emit {@code BigDecimal.ZERO}
     *  for a missing open) must not be walked as if it were a real entry -- the guard falls back
     *  to reference_price, exactly like the "no bar yet" branch, rather than handing engine.walk
     *  a zero entry that would produce a garbage R marked complete. */
    @Test
    void aNonPositiveFirstBarOpenFallsBackToReferencePrice() {
        String signalId = "sig-skip-zeroopen";
        wireSkip(signalId, "ZEROOPENCO", skippedSignal(signalId, "ZEROOPENCO"),
                zeroOpenBarsFrom(LocalDate.parse("2026-09-04"), 70));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();
        // entry     = reference_price                     = 100 (the zero open is rejected)
        // stop      = deriveStopAnchor(BUY, 100, atr 2, null) = 100 - 2.5*2 = 95
        // rPerShare = |100 - 95|                           = 5
        // after[19] = the 20th walked bar, closes ramp 101..120, so price = 120
        // r_after_20d = (120 - 100) / 5                    = 4.0
        assertThat(row.hypothetical().path("entry_source").asString()).isEqualTo("reference_price");
        assertThat(row.hypothetical().path("entry_price").asDouble()).isEqualTo(100.0);
        assertThat(row.hypothetical().path("r_after_20d").asDouble()).isEqualTo(4.0);
        assertThat(row.hypothetical().path("skipped_reason").isNull()).isTrue();
        assertThat(row.complete()).isTrue();
    }

    /** The walk itself can skip (atr 0 -> stop == entry -> non-positive rPerShare). That row still
     *  reached engine.walk with a chosen entry, so it carries the entry keys: without them an
     *  operator reading a skipped row could not tell which price the engine was handed. */
    @Test
    void walkThatItselfSkipsStillCarriesEntrySource() {
        String signalId = "sig-skip-zeroatr";
        ExecutorSignal zeroAtr = new ExecutorSignal(signalId, "strigoi-spin", "v1", "ZEROCO", "BUY",
                0.7, "SPINOFF", List.of(), "3m", bd("100"), "SKIPPED", null, null, null,
                LocalDate.parse("2026-09-04"), bd("0"));
        wireSkip(signalId, "ZEROCO", zeroAtr, risingBarsFrom(LocalDate.parse("2026-09-04"), 70));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();
        assertThat(row.hypothetical().path("skipped_reason").isNull()).isFalse();
        assertThat(row.hypothetical().path("entry_source").asString()).isEqualTo("next_bar_open");
        assertThat(row.hypothetical().path("entry_price").asDouble()).isEqualTo(101.0);
    }

    /** (regression) The three branches that return BEFORE engine.walk never chose an entry, so
     *  they must not pretend they did: no entry_source, no entry_price. One run, three decisions,
     *  one per branch -- signal unresolvable, reference fields missing, and the source serving no
     *  bars at all. Passes on a no-op implementation and pins the absence after SP8. */
    @Test
    void skippedBranchesCarryNoEntrySource() {
        when(positions.findClosed()).thenReturn(List.of());
        when(decisionLog.findSignalRowsByAction("REJECT")).thenReturn(List.of());
        when(executorDecisions.findSkipsWithoutDecisionLog()).thenReturn(List.of(
                skipDecision("sig-noside", "NOSIDECO"),
                skipDecision("sig-noref", "NOREFCO"),
                skipDecision("sig-nodata", "NODATACO")));
        when(signals.findById("sig-noside")).thenReturn(null);
        when(signals.findById("sig-noref")).thenReturn(new ExecutorSignal("sig-noref",
                "strigoi-spin", "v1", "NOREFCO", "BUY", 0.7, "SPINOFF", List.of(), "3m", null,
                "SKIPPED", null, null, null, LocalDate.parse("2026-09-04"), bd("2")));
        when(signals.findById("sig-nodata")).thenReturn(skippedSignal("sig-nodata", "NODATACO"));
        when(outcomeLog.isComplete(anyString())).thenReturn(false);
        when(ruleVersions.active()).thenReturn("exec-v0.6");
        when(marketData.dailyOhlcHistory(eq("NODATACO"), anyInt())).thenReturn(List.of());

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog, times(3)).upsert(captor.capture());
        assertThat(captor.getAllValues()).hasSize(3);
        for (OutcomeLogRow row : captor.getAllValues()) {
            assertThat(row.hypothetical().has("entry_source"))
                    .as("entry_source on %s", row.logIdRef()).isFalse();
            assertThat(row.hypothetical().has("entry_price"))
                    .as("entry_price on %s", row.logIdRef()).isFalse();
            assertThat(row.hypothetical().path("skipped_reason").isNull())
                    .as("skipped_reason on %s", row.logIdRef()).isFalse();
        }
    }

    /** The swept path shares processSignalAnchored, so a SIGNAL_EXPIRED_UNEVALUATED row must carry
     *  exactly the same entry convention -- otherwise the two counterfactual populations would be
     *  measured from different prices and could not be pooled. */
    @Test
    void sweptSignalUsesTheSameEntryConvention() {
        String signalId = "sig-swept-entry";
        LocalDate anchor = LocalDate.parse("2026-09-04");
        wireSwept(signalId, "SWEEPCO", sweptSignal(signalId, "SWEEPCO", "REJECTED", anchor),
                risingBarsFrom(anchor, 70));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        OutcomeLogRow row = captor.getValue();
        assertThat(row.reasonCode()).isEqualTo("SIGNAL_EXPIRED_UNEVALUATED");
        assertThat(row.hypothetical().path("entry_source").asString()).isEqualTo("next_bar_open");
        assertThat(row.hypothetical().path("entry_price").asDouble()).isEqualTo(101.0);
        assertThat(row.hypothetical().path("r_after_20d").asDouble()).isEqualTo(3.8);
    }

    /** (regression) fetchBarsAfter's date filter is STRICT, so the anchor bar is never walked.
     *  The fixture's anchor bar dips to a low of 90, below every stop this fixture can produce
     *  (95 pre-SP8, 96 after), while no bar after it goes below 101. would_have_stopped_out is
     *  therefore false only as long as the anchor bar stays out of the walk -- true before and
     *  after SP8, which is what makes this a regression guard rather than a new assertion. */
    @Test
    void anchorBarIsNotPartOfTheWalkedBars() {
        String signalId = "sig-skip-anchorbar";
        wireSkip(signalId, "DIPCO", skippedSignal(signalId, "DIPCO"),
                anchorBarDipsFrom(LocalDate.parse("2026-09-04"), 25));

        job.run();

        ArgumentCaptor<OutcomeLogRow> captor = ArgumentCaptor.forClass(OutcomeLogRow.class);
        verify(outcomeLog).upsert(captor.capture());
        assertThat(captor.getValue().hypothetical().path("would_have_stopped_out").asBoolean())
                .isFalse();
    }

    /** (d) An already-complete swept row is not re-walked (the isComplete short-circuit, shared
     *  with the LLM_SKIP path). */
    @Test
    void sweptSignal_alreadyComplete_isNotReWalked() {
        String signalId = "sig-swept-done";
        LocalDate anchor = LocalDate.parse("2026-09-04");
        wireSwept(signalId, "DONECO", sweptSignal(signalId, "DONECO", "REJECTED", anchor),
                risingBarsFrom(anchor, 70));
        when(outcomeLog.isComplete("expired:" + signalId)).thenReturn(true);

        job.run();

        verify(outcomeLog, org.mockito.Mockito.never()).upsert(any());
        verify(marketData, org.mockito.Mockito.never()).dailyOhlcHistory(anyString(), anyInt());
    }
}
