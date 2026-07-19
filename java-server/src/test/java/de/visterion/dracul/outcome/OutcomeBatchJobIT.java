package de.visterion.dracul.outcome;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.executor.DecisionLog;
import de.visterion.dracul.executor.DecisionLogRepository;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignal;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {"dracul.executor.enabled=true", "dracul.outcome.enabled=true"})
class OutcomeBatchJobIT {

    @Autowired OutcomeBatchJob job;
    @Autowired OutcomeLogRepository outcomeLogRepo;
    @Autowired DecisionLogRepository decisionLogRepo;
    @Autowired ExecutorPositionRepository positionRepo;
    @Autowired ExecutorSignalRepository signalRepo;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @MockitoBean AgoraMarketData marketData;

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private ExecutorPosition openPosition(String symbol, String signalId, BigDecimal qty) {
        return new ExecutorPosition(
                null, "depot-1", symbol, "BUY", qty, bd("100"), bd("95"), bd("95"), 1, bd("5"),
                List.of(), signalId, "strigoi-spin", null, null, "OPEN", null, bd("100"), null, 0,
                null, null, null, null, null, null, null, null, null, 0, null, null, null, null, null, null);
    }

    /** One bar per calendar day starting {@code start}, close/high/low derived from a simple offset. */
    private static List<OhlcBar> driftUpBars(LocalDate start, int n) {
        List<OhlcBar> bars = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            BigDecimal close = bd("100").add(bd("0.5").multiply(BigDecimal.valueOf(i)));
            BigDecimal high = close.add(bd("0.5"));
            BigDecimal low = close.subtract(bd("0.5"));
            bars.add(new OhlcBar(start.plusDays(i), close, high, low, close, 1_000L));
        }
        return bars;
    }

    @Test
    void tradeRecord_quantityWeightedRealizedR() {
        String symbol = "OTRD" + System.nanoTime();
        String signalId = "sig-" + symbol;

        // ENTER decision row: order_price/atr present (unused by TRADE, but realistic),
        // limit_price 99 -> slippage_vs_limit = entryPrice(100) - limit(99) = 1.
        var inputsSnapshot = mapper.readTree("{\"order_price\":100,\"atr\":2}");
        var enterOrderJson = (tools.jackson.databind.node.ObjectNode) mapper.createObjectNode();
        enterOrderJson.put("limit_price", bd("99"));
        decisionLogRepo.insert(new DecisionLog(null, "run-1", "exec-v0.2", "SIGNAL", signalId,
                "strigoi-spin", "v1", symbol, inputsSnapshot, null, "ENTER", null,
                enterOrderJson, null, 0.9, null, null));
        DecisionLog enter = decisionLogRepo.findBySignalIdAndAction(signalId, "ENTER");
        assertThat(enter).isNotNull();

        // Position: entry 100 qty 100, initial stop 95 -> rPerShare 5.
        long positionId = positionRepo.insert(openPosition(symbol, signalId, bd("100")));

        // Trim 33 @ 110 (R = (110-100)/5 = 2.0).
        positionRepo.recordTrim(positionId, bd("67"), 1);
        var trimOrderJson = (tools.jackson.databind.node.ObjectNode) mapper.createObjectNode();
        trimOrderJson.put("fraction", 0.33);
        trimOrderJson.put("qty_closed", bd("33"));
        trimOrderJson.put("qty_remaining", bd("67"));
        trimOrderJson.put("price", bd("110"));
        decisionLogRepo.insert(new DecisionLog(null, "run-1", "exec-v0.2", "SOFT_TRIGGER", null,
                null, null, symbol, null, null, "TRIM", null, trimOrderJson, null, null, null, null));

        // Final exit 67 @ 104 (R = (104-100)/5 = 0.8).
        positionRepo.updateAdverseExtreme(positionId, bd("93")); // MAE = (93-100)/5 = -1.4
        positionRepo.close(positionId, bd("104"), bd("0.8"), "TAKE_PROFIT");
        var exitInputs = mapper.readTree("{\"exit_price\":104,\"realized_r\":0.8}");
        decisionLogRepo.insert(new DecisionLog(null, "run-1", "exec-v0.2", "SOFT_TRIGGER", null,
                null, null, symbol, exitInputs, null, "EXIT_FULL", "TAKE_PROFIT", null, null, null, null, null));

        job.run();

        OutcomeLogRow row = outcomeLogRepo.findByLogIdRef(enter.logId());
        assertThat(row).isNotNull();
        assertThat(row.kind()).isEqualTo("TRADE");
        assertThat(row.symbol()).isEqualTo(symbol);
        assertThat(row.filled()).isTrue();
        assertThat(row.fillPrice()).isEqualByComparingTo("100");
        assertThat(row.slippageVsLimit()).isEqualByComparingTo("1");
        // weighted R = (33*2.0 + 67*0.8) / 100 = 1.196
        assertThat(row.realizedR()).isEqualByComparingTo("1.196");
        assertThat(row.maeR()).isEqualByComparingTo("-1.4");
        assertThat(row.exitTrigger()).isEqualTo("TAKE_PROFIT");
        assertThat(row.partialExits()).isNotNull();
        assertThat(row.partialExits().get(0).path("price").asDouble()).isEqualTo(110.0);
        assertThat(row.roundtripUnder5d()).isNotNull();
        // Closed just now -> inside the 14-calendar-day re-entry window, so the row must stay
        // incomplete (re-runs keep recomputing reentry_within_10d until the window elapses).
        assertThat(row.complete()).isFalse();
        assertThat(row.sourceAgent()).isEqualTo("strigoi-spin");
        assertThat(row.agentVersion()).isEqualTo("v1");
        assertThat(row.ruleVersion()).isEqualTo("exec-v0.2");

        // idempotent re-run: still exactly one row for this log_id_ref
        job.run();
        OutcomeLogRow again = outcomeLogRepo.findByLogIdRef(enter.logId());
        assertThat(again.realizedR()).isEqualByComparingTo(row.realizedR());
    }

    /**
     * Regression: a TRIM whose {@code created_at} lands on the UTC calendar day BEFORE the
     * position's {@code entry_date} must still be quantity-weighted into realized R. This is
     * exactly what a timezone-skewed nightly run produces — the position's entry_date renders in
     * local time one day ahead of the trim's actual UTC instant (e.g. the {@code 22:30 UTC} cron
     * fires at {@code 00:30} in a UTC+2 deployment). The trim-linkage window must tolerate ±1
     * calendar day either side, or the trim is dropped and realized R collapses to the final
     * leg only (0.8 instead of 1.196), silently corrupting calibration data.
     */
    @Test
    void tradeRecord_trimOnPriorUtcDay_stillQuantityWeighted() {
        String symbol = "OTRDTZ" + System.nanoTime();
        String signalId = "sig-" + symbol;

        var inputsSnapshot = mapper.readTree("{\"order_price\":100,\"atr\":2}");
        var enterOrderJson = (tools.jackson.databind.node.ObjectNode) mapper.createObjectNode();
        enterOrderJson.put("limit_price", bd("99"));
        decisionLogRepo.insert(new DecisionLog(null, "run-1", "exec-v0.2", "SIGNAL", signalId,
                "strigoi-spin", "v1", symbol, inputsSnapshot, null, "ENTER", null,
                enterOrderJson, null, 0.9, null, null));
        DecisionLog enter = decisionLogRepo.findBySignalIdAndAction(signalId, "ENTER");

        long positionId = positionRepo.insert(openPosition(symbol, signalId, bd("100")));

        // Trim 33 @ 110 (R = 2.0). Backdate its created_at to noon on the prior UTC calendar day,
        // simulating a timezone-skewed run where entry_date's local date is one day ahead.
        positionRepo.recordTrim(positionId, bd("67"), 1);
        var trimOrderJson = (tools.jackson.databind.node.ObjectNode) mapper.createObjectNode();
        trimOrderJson.put("fraction", 0.33);
        trimOrderJson.put("qty_closed", bd("33"));
        trimOrderJson.put("qty_remaining", bd("67"));
        trimOrderJson.put("price", bd("110"));
        decisionLogRepo.insert(new DecisionLog(null, "run-1", "exec-v0.2", "SOFT_TRIGGER", null,
                null, null, symbol, null, null, "TRIM", null, trimOrderJson, null, null, null, null));
        var priorUtcDayNoon = java.sql.Timestamp.from(
                java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1)
                        .atTime(12, 0).toInstant(java.time.ZoneOffset.UTC));
        int updated = jdbc.sql("UPDATE decision_log SET created_at = :ts WHERE symbol = :sym AND action = 'TRIM'")
                .param("ts", priorUtcDayNoon).param("sym", symbol).update();
        assertThat(updated).isEqualTo(1);

        // Final exit 67 @ 104 (R = 0.8).
        positionRepo.updateAdverseExtreme(positionId, bd("93"));
        positionRepo.close(positionId, bd("104"), bd("0.8"), "TAKE_PROFIT");
        var exitInputs = mapper.readTree("{\"exit_price\":104,\"realized_r\":0.8}");
        decisionLogRepo.insert(new DecisionLog(null, "run-1", "exec-v0.2", "SOFT_TRIGGER", null,
                null, null, symbol, exitInputs, null, "EXIT_FULL", "TAKE_PROFIT", null, null, null, null, null));

        job.run();

        OutcomeLogRow row = outcomeLogRepo.findByLogIdRef(enter.logId());
        assertThat(row).isNotNull();
        // weighted R = (33*2.0 + 67*0.8) / 100 = 1.196 — trim must NOT be dropped by the window.
        assertThat(row.realizedR()).isEqualByComparingTo("1.196");
        assertThat(row.partialExits()).isNotNull();
        assertThat(row.partialExits().get(0).path("price").asDouble()).isEqualTo(110.0);
    }

    @Test
    void counterfactualRecord_incompleteUntil60Bars_thenComplete() {
        String symbol = "OCFT" + System.nanoTime();
        String signalId = "sig-" + symbol;

        signalRepo.insert(new ExecutorSignal(signalId, "strigoi-spin", "v1", symbol, "BUY",
                0.7, "SPINOFF", List.of(), "3m", bd("100"), "REJECTED", null));

        var inputsSnapshot = mapper.readTree("{\"order_price\":100,\"atr\":2}");
        decisionLogRepo.insert(new DecisionLog(null, "run-2", "exec-v0.2", "SIGNAL", signalId,
                "strigoi-spin", "v1", symbol, inputsSnapshot, null, "REJECT", "PACE_LIMIT",
                null, null, null, null, null));
        DecisionLog reject = decisionLogRepo.findBySymbolAndAction(symbol, "REJECT").get(0);

        // First run: only 20 bars available -> incomplete, r_after_20d computed, r_after_60d null.
        when(marketData.dailyOhlcHistory(anyString(), anyInt()))
                .thenReturn(driftUpBars(LocalDate.now(), 20));

        job.run();

        OutcomeLogRow first = outcomeLogRepo.findByLogIdRef(reject.logId());
        assertThat(first).isNotNull();
        assertThat(first.kind()).isEqualTo("COUNTERFACTUAL");
        assertThat(first.reasonCode()).isEqualTo("PACE_LIMIT");
        assertThat(first.hypothetical().path("r_after_20d").asDouble()).isEqualTo(2.0);
        assertThat(first.hunterLabel()).isTrue();
        assertThat(first.complete()).isFalse();

        // Re-run with 60 bars now available -> idempotent update (still one row), now complete.
        when(marketData.dailyOhlcHistory(anyString(), anyInt()))
                .thenReturn(driftUpBars(LocalDate.now(), 60));

        job.run();

        OutcomeLogRow second = outcomeLogRepo.findByLogIdRef(reject.logId());
        assertThat(second).isNotNull();
        assertThat(second.complete()).isTrue();
        assertThat(second.hypothetical().path("r_after_60d").isNull()).isFalse();
    }

    @Test
    void counterfactualRecord_missingInputs_skippedAndComplete() {
        String symbol = "OCFS" + System.nanoTime();

        // No signal_id, no inputs_snapshot -> unresolvable side + missing order_price/atr.
        decisionLogRepo.insert(new DecisionLog(null, "run-3", "exec-v0.2", "SIGNAL", null,
                "strigoi-spin", "v1", symbol, null, null, "REJECT", "LOW_CONFIDENCE",
                null, null, null, null, null));
        DecisionLog reject = decisionLogRepo.findBySymbolAndAction(symbol, "REJECT").get(0);

        job.run();

        OutcomeLogRow row = outcomeLogRepo.findByLogIdRef(reject.logId());
        assertThat(row).isNotNull();
        assertThat(row.complete()).isTrue();
        assertThat(row.hypothetical().path("skipped_reason").isNull()).isFalse();
        assertThat(row.hypothetical().path("skipped_reason").asString()).contains("unresolvable");
    }
}
