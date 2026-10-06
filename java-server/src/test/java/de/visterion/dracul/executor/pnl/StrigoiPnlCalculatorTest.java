package de.visterion.dracul.executor.pnl;

import de.visterion.dracul.executor.DecisionLog;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionFixtures;
import de.visterion.dracul.executor.pnl.StrigoiPnlCalculator.Inputs;
import de.visterion.dracul.executor.pnl.StrigoiPnlCalculator.Priced;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 2026-10-06 (Tests → Backend unit). All symbols, agents and prices are synthetic. */
class StrigoiPnlCalculatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final UnaryOperator<BigDecimal> IDENTITY = a -> a;
    private static final UnaryOperator<BigDecimal> RATE_0_9 = a -> a.multiply(new BigDecimal("0.9"));
    private static final UnaryOperator<BigDecimal> NO_FX = a -> null;
    private static final Function<String, BigDecimal> NO_CLOSE = s -> null;

    private static BigDecimal bd(String v) { return v == null ? null : new BigDecimal(v); }

    /** One book row. Dates use the Timestamp.toString() shape the repository produces. */
    private static ExecutorPosition pos(long id, String agent, String symbol, String side, String status,
            String qty, String entry, String exit, String realizedR, String entryDate, String closedAt,
            String entryFilledAt) {
        return ExecutorPositionFixtures.withoutKillLevel(id, "depot-1", symbol, side,
                bd(qty), bd(entry), bd("1"), bd("1"), 1, null, List.of(), "sig-" + id, agent,
                entryDate, null, status, "brk-" + id, null, null, 0, bd(exit), bd(realizedR),
                "CLOSED".equals(status) ? "HARD_STOP" : null, closedAt, null, null, null, null, null, 0,
                null, null, null, null, null, null, false, null, entryFilledAt);
    }

    private static ExecutorPosition closed(long id, String agent, String symbol, String qty, String entry,
            String exit, String r, String closedAt) {
        return pos(id, agent, symbol, "BUY", "CLOSED", qty, entry, exit, r,
                "2026-09-01 10:00:00.0", closedAt, "2026-09-01 15:00:00.0");
    }

    private static ExecutorPosition open(long id, String agent, String symbol, String qty, String entry) {
        return pos(id, agent, symbol, "BUY", "OPEN", qty, entry, null, null,
                "2026-09-05 10:00:00.0", null, "2026-09-05 15:00:00.0");
    }

    private static DecisionLog leg(String action, String symbol, String createdAt, String orderJson) {
        return new DecisionLog("log-" + createdAt, "run-syn", "exec-test", "MAINTENANCE", null, null,
                null, symbol, null, null, action, action, MAPPER.readTree(orderJson), null, null, null,
                createdAt);
    }

    private static Inputs in(List<ExecutorPosition> ps, List<DecisionLog> legs, Map<String, BigDecimal> broker,
            Function<String, BigDecimal> close, UnaryOperator<BigDecimal> toEur) {
        return new Inputs(ps, legs, broker, close, toEur, "USD", NOW);
    }

    private static PnlTrade only(List<Priced> priced) {
        assertThat(priced).hasSize(1);
        return priced.getFirst().trade();
    }

    @Test
    void closedTradeWithoutTrim() {
        PnlTrade t = only(StrigoiPnlCalculator.price(in(List.of(
                closed(1, "strigoi-syna", "SYNA", "10", "100", "110", "0.5", "2026-09-20 15:00:00.0")),
                List.of(), Map.of(), NO_CLOSE, IDENTITY)));

        assertThat(t.realizedEur()).isEqualByComparingTo("100.00");
        assertThat(t.unrealizedEur()).isNull();
        assertThat(t.r()).isEqualByComparingTo("0.5");
        assertThat(t.entryDate()).isEqualTo("2026-09-01");
        assertThat(t.exitDate()).isEqualTo("2026-09-20");
        assertThat(t.exitPrice()).isEqualByComparingTo("110");
        assertThat(t.currency()).isEqualTo("USD");
        assertThat(t.flags()).isEmpty();
    }

    @Test
    void trimLegsWithOwnEntryPlusFinalLeg() {
        // Average entry 100 after a savings add; the first trim sold the add lot bought at 90.
        List<DecisionLog> legs = List.of(
                leg("TRIM", "SYNB", "2026-09-10 16:00:00.0",
                        "{\"qty_closed\":4,\"price\":120,\"entry_price\":90,\"position_id\":2}"),
                leg("TRIM", "SYNB", "2026-09-11 16:00:00.0",
                        "{\"qty_closed\":3,\"price\":null,\"order_id\":\"ord-syn-2\",\"position_id\":2}"),
                leg("TRIM_FILL", "SYNB", "2026-09-12 16:00:00.0",
                        "{\"order_id\":\"ord-syn-2\",\"qty\":2,\"price\":105,\"position_id\":2}"),
                // another position's trim and a pre-linkage row outside the window: both ignored
                leg("TRIM", "SYNB", "2026-09-10 17:00:00.0",
                        "{\"qty_closed\":50,\"price\":500,\"position_id\":99}"),
                leg("TRIM", "SYNB", "2026-08-01 10:00:00.0", "{\"qty_closed\":1,\"price\":1000}"));

        PnlTrade t = only(StrigoiPnlCalculator.price(in(List.of(
                closed(2, "strigoi-syna", "SYNB", "6", "100", "95", "-0.25", "2026-09-25 15:00:00.0")),
                legs, Map.of(), NO_CLOSE, IDENTITY)));

        // (120-90)*4 + (105-100)*2 + (95-100)*6 = 120 + 10 - 30
        assertThat(t.realizedEur()).isEqualByComparingTo("100.00");
        assertThat(t.flags()).isEmpty();
    }

    @Test
    void unlinkedTrimInsideTheWindowCounts() {
        PnlTrade t = only(StrigoiPnlCalculator.price(in(List.of(
                closed(3, "strigoi-syna", "SYNC", "5", "10", "12", "1", "2026-09-20 15:00:00.0")),
                List.of(leg("TRIM", "SYNC", "2026-09-10 16:00:00.0", "{\"qty_closed\":5,\"price\":11}")),
                Map.of(), NO_CLOSE, IDENTITY)));

        assertThat(t.realizedEur()).isEqualByComparingTo("15.00"); // (11-10)*5 + (12-10)*5
    }

    @Test
    void trimWithoutPriceIsIncompleteNeverZero() {
        List<Priced> priced = StrigoiPnlCalculator.price(in(List.of(
                closed(4, "strigoi-syna", "SYND", "6", "100", "120", "1", "2026-09-20 15:00:00.0")),
                List.of(leg("TRIM", "SYND", "2026-09-10 16:00:00.0",
                        "{\"qty_closed\":2,\"price\":null,\"order_id\":\"ord-syn-4\",\"position_id\":4}")),
                Map.of(), NO_CLOSE, IDENTITY));

        assertThat(only(priced).realizedEur()).isNull();
        assertThat(only(priced).flags()).containsExactly(StrigoiPnlCalculator.FLAG_INCOMPLETE_LEGS);
        StrigoiPnlSummary s = StrigoiPnlCalculator.summarize("strigoi-syna", priced);
        assertThat(s.realizedEur()).isEqualByComparingTo("0.00");
        assertThat(s.closedTrades()).isEqualTo(1);
        assertThat(s.wins()).isZero();
        assertThat(s.losses()).isZero();
        assertThat(s.flaggedTrades()).isEqualTo(1);
    }

    @Test
    void openPositionUsesBrokerMarketPriceAndNeverAsksForTheClose() {
        List<String> closeCalls = new ArrayList<>();
        PnlTrade t = only(StrigoiPnlCalculator.price(in(List.of(open(5, "strigoi-syna", "syne", "5", "50")),
                List.of(), Map.of("SYNE", new BigDecimal("60")),
                s -> { closeCalls.add(s); return new BigDecimal("1"); }, IDENTITY)));

        assertThat(t.unrealizedEur()).isEqualByComparingTo("50.00");
        assertThat(t.realizedEur()).isEqualByComparingTo("0.00");
        assertThat(t.exitDate()).isNull();
        assertThat(t.exitPrice()).isNull();
        assertThat(t.r()).isNull();
        assertThat(closeCalls).isEmpty();
    }

    @Test
    void openPositionFallsBackToTheLastClose() {
        PnlTrade t = only(StrigoiPnlCalculator.price(in(List.of(open(6, "strigoi-syna", "SYNA", "5", "50")),
                List.of(), Map.of(), s -> new BigDecimal("45"), IDENTITY)));

        assertThat(t.unrealizedEur()).isEqualByComparingTo("-25.00");
        assertThat(t.flags()).isEmpty();
    }

    @Test
    void openPositionWithoutAnyPriceIsNullNeverZero() {
        List<Priced> priced = StrigoiPnlCalculator.price(in(List.of(open(7, "strigoi-syna", "SYNA", "5", "50")),
                List.of(), Map.of(), NO_CLOSE, IDENTITY));

        assertThat(only(priced).unrealizedEur()).isNull();
        assertThat(only(priced).flags()).containsExactly(StrigoiPnlCalculator.FLAG_NO_PRICE);
        StrigoiPnlSummary s = StrigoiPnlCalculator.summarize("strigoi-syna", priced);
        assertThat(s.unrealizedEur()).isEqualByComparingTo("0.00");
        assertThat(s.openPositions()).isEqualTo(1);
        assertThat(s.openCostEur()).isEqualByComparingTo("250.00");
        assertThat(s.flaggedTrades()).isEqualTo(1);
    }

    @Test
    void trimOfAnOpenPositionIsRealized() {
        PnlTrade t = only(StrigoiPnlCalculator.price(in(List.of(open(8, "strigoi-syna", "SYNB", "5", "50")),
                List.of(leg("TRIM", "SYNB", "2026-09-10 16:00:00.0",
                        "{\"qty_closed\":2,\"price\":55,\"position_id\":8}")),
                Map.of("SYNB", new BigDecimal("60")), NO_CLOSE, IDENTITY)));

        assertThat(t.realizedEur()).isEqualByComparingTo("10.00");
        assertThat(t.unrealizedEur()).isEqualByComparingTo("50.00");
    }

    @Test
    void convertsToEurAtTheGivenRate() {
        List<Priced> priced = StrigoiPnlCalculator.price(in(List.of(
                closed(9, "strigoi-syna", "SYNA", "10", "100", "110", "0.5", "2026-09-20 15:00:00.0"),
                open(10, "strigoi-syna", "SYNB", "5", "50")),
                List.of(), Map.of("SYNB", new BigDecimal("60")), NO_CLOSE, RATE_0_9));

        StrigoiPnlSummary s = StrigoiPnlCalculator.summarize("strigoi-syna", priced);
        assertThat(s.realizedEur()).isEqualByComparingTo("90.00");
        assertThat(s.unrealizedEur()).isEqualByComparingTo("45.00");
        assertThat(s.totalEur()).isEqualByComparingTo("135.00");
        assertThat(s.openCostEur()).isEqualByComparingTo("225.00");
    }

    @Test
    void missingFxRateIsNullButStillClassifiesTheTrade() {
        List<Priced> priced = StrigoiPnlCalculator.price(in(List.of(
                closed(11, "strigoi-syna", "SYNA", "10", "100", "110", "0.5", "2026-09-20 15:00:00.0")),
                List.of(), Map.of(), NO_CLOSE, NO_FX));

        assertThat(only(priced).realizedEur()).isNull();
        assertThat(only(priced).flags()).containsExactly(StrigoiPnlCalculator.FLAG_NO_FX);
        assertThat(StrigoiPnlCalculator.summarize("strigoi-syna", priced).wins()).isEqualTo(1);
    }

    @Test
    void groupsBySourceAgentWithNullAndBlankAsUnknownLast() {
        List<Priced> priced = StrigoiPnlCalculator.price(in(List.of(
                closed(12, "strigoi-synb", "SYNA", "1", "10", "11", "1", "2026-09-20 15:00:00.0"),
                closed(13, "strigoi-syna", "SYNB", "1", "10", "11", "1", "2026-09-20 15:00:00.0"),
                closed(14, null, "SYNC", "1", "10", "9", "-1", "2026-09-20 15:00:00.0"),
                closed(15, " ", "SYND", "1", "10", "9", "-1", "2026-09-20 15:00:00.0")),
                List.of(), Map.of(), NO_CLOSE, IDENTITY));

        List<StrigoiPnlSummary> rows = StrigoiPnlCalculator.overview(priced);
        assertThat(rows).extracting(StrigoiPnlSummary::strigoi)
                .containsExactly("strigoi-syna", "strigoi-synb", StrigoiPnlCalculator.UNKNOWN);
        assertThat(rows.get(2).closedTrades()).isEqualTo(2);
        assertThat(rows.get(2).losses()).isEqualTo(2);
    }

    @Test
    void cancelledAndUnfilledOpenRowsAreNotTrades() {
        ExecutorPosition cancelled = pos(16, "strigoi-syna", "SYNA", "BUY", "CANCELLED", "5", "50", null, null,
                "2026-09-05 10:00:00.0", "2026-09-08 10:00:00.0", null);
        ExecutorPosition unfilled = pos(17, "strigoi-syna", "SYNB", "BUY", "OPEN", "5", "50", null, null,
                "2026-09-05 10:00:00.0", null, null);

        assertThat(StrigoiPnlCalculator.price(in(List.of(cancelled, unfilled), List.of(),
                Map.of("SYNB", new BigDecimal("60")), NO_CLOSE, IDENTITY))).isEmpty();
    }

    @Test
    void winLossAndZero() {
        List<Priced> priced = StrigoiPnlCalculator.price(in(List.of(
                closed(18, "strigoi-syna", "SYNA", "1", "10", "12", "2", "2026-09-20 15:00:00.0"),
                closed(19, "strigoi-syna", "SYNB", "1", "10", "9", "-1", "2026-09-21 15:00:00.0"),
                closed(20, "strigoi-syna", "SYNC", "1", "10", "10", "0", "2026-09-22 15:00:00.0")),
                List.of(), Map.of(), NO_CLOSE, IDENTITY));

        StrigoiPnlSummary s = StrigoiPnlCalculator.summarize("strigoi-syna", priced);
        assertThat(s.closedTrades()).isEqualTo(3);
        assertThat(s.wins()).isEqualTo(1);
        assertThat(s.losses()).isEqualTo(1);
        assertThat(s.hitRate()).isEqualByComparingTo("0.3333");
        assertThat(s.sumR()).isEqualByComparingTo("1");
        assertThat(s.realizedEur()).isEqualByComparingTo("1.00");
    }

    @Test
    void noClosedTradesMeansNoHitRate() {
        StrigoiPnlSummary s = StrigoiPnlCalculator.summarize("strigoi-syna", List.of());
        assertThat(s.hitRate()).isNull();
        assertThat(s.totalEur()).isEqualByComparingTo("0.00");
    }

    @Test
    void sellSideIsMirrored() {
        PnlTrade t = only(StrigoiPnlCalculator.price(in(List.of(
                pos(21, "strigoi-syna", "SYNA", "sell", "CLOSED", "10", "100", "90", "1",
                        "2026-09-01 10:00:00.0", "2026-09-20 15:00:00.0", "2026-09-01 15:00:00.0")),
                List.of(), Map.of(), NO_CLOSE, IDENTITY)));

        assertThat(t.realizedEur()).isEqualByComparingTo("100.00");
    }

    @Test
    void sortsOpenFirstThenNewestClose() {
        List<Priced> priced = StrigoiPnlCalculator.price(in(List.of(
                closed(22, "strigoi-syna", "SYNA", "1", "10", "11", "1", "2026-09-10 15:00:00.0"),
                open(23, "strigoi-syna", "SYNB", "1", "10"),
                closed(24, "strigoi-syna", "SYNC", "1", "10", "11", "1", "2026-09-20 15:00:00.0")),
                List.of(), Map.of("SYNB", new BigDecimal("11")), NO_CLOSE, IDENTITY));

        assertThat(StrigoiPnlCalculator.sortedTrades(priced)).extracting(PnlTrade::positionId)
                .containsExactly(23L, 24L, 22L);
    }
}
