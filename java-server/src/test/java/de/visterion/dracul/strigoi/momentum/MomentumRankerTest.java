package de.visterion.dracul.strigoi.momentum;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MomentumRankerTest {

    static final MomentumSettings SETTINGS = new MomentumSettings(10, 10, 3, 480,
            new BigDecimal("0.95"), new BigDecimal("0.025"), 252, 21, new BigDecimal("0.35"),
            new BigDecimal("5"), 1, Set.of(), "depot-1");
    static final LocalDate AS_OF = LocalDate.parse("2026-10-30");

    /** roc231 series of 30 values; series[-22] (index 8) carries the momentum, the others are noise. */
    static List<BigDecimal> rocLong(String momentum) {
        List<BigDecimal> s = new ArrayList<>(Collections.nCopies(30, new BigDecimal("-99")));
        s.set(30 - 22, new BigDecimal(momentum));
        return s;
    }

    static List<BigDecimal> roc1(String fill, Map<Integer, String> overrides) {
        List<BigDecimal> s = new ArrayList<>(Collections.nCopies(250, new BigDecimal(fill)));
        overrides.forEach((i, v) -> s.set(i, new BigDecimal(v)));
        return s;
    }

    static MomentumBarsClient.Series ok(String symbol, String momentum, String close, List<BigDecimal> roc1) {
        return new MomentumBarsClient.Series(symbol, MomentumBarsClient.Status.OK, new BigDecimal(close),
                AS_OF, rocLong(momentum), roc1);
    }

    @Test
    void twelveMinusOneIsSeriesMinus22AndTheOrderIsDescendingWithSymbolTieBreak() {
        Map<String, MomentumBarsClient.Series> data = new HashMap<>();
        data.put("SYNA", ok("SYNA", "12.34", "50", roc1("0.1", Map.of())));
        data.put("SYNB", ok("SYNB", "40.00", "50", roc1("0.1", Map.of())));
        data.put("SYNC", ok("SYNC", "12.34", "50", roc1("0.1", Map.of())));

        var r = MomentumRanker.rank(List.of("SYNA", "SYNB", "SYNC"), data, SETTINGS);

        assertThat(r.ranked()).extracting(MomentumRanker.Ranked::symbol).containsExactly("SYNB", "SYNA", "SYNC");
        assertThat(r.ranked()).extracting(MomentumRanker.Ranked::rank).containsExactly(1, 2, 3);
        assertThat(r.ranked().get(1).momentumPct()).isEqualByComparingTo("12.34");
        assertThat(r.asOf()).isEqualTo(AS_OF);
        assertThat(r.universeSize()).isEqualTo(3);
    }

    @Test
    void gapRulesInBothDirections() {
        Map<String, MomentumBarsClient.Series> data = new HashMap<>();
        data.put("SYND", ok("SYND", "10", "50", roc1("0.1", Map.of(100, "-35.00"))));   // suspect
        data.put("SYNE", ok("SYNE", "10", "50", roc1("0.1", Map.of(100, "-34.99"))));   // medium gap
        data.put("SYNF", ok("SYNF", "10", "50", roc1("0.1", Map.of(100, "53.80"))));    // suspect (reverse split)
        data.put("SYNG", ok("SYNG", "10", "50", roc1("0.1", Map.of(100, "53.79"))));    // ranked
        data.put("SYNH", ok("SYNH", "10", "50", roc1("0.1", Map.of(100, "-15.00"))));   // medium gap
        data.put("SYNI", ok("SYNI", "10", "50", roc1("0.1", Map.of(100, "-14.99"))));   // plain

        var r = MomentumRanker.rank(List.of("SYND", "SYNE", "SYNF", "SYNG", "SYNH", "SYNI"), data, SETTINGS);

        assertThat(r.unranked()).containsEntry("SYND", MomentumRanker.DATA_SUSPECT)
                .containsEntry("SYNF", MomentumRanker.DATA_SUSPECT);
        assertThat(r.suspects()).extracting(MomentumRanker.Suspect::symbol).containsExactlyInAnyOrder("SYND", "SYNF");
        assertThat(r.find("SYNE").orElseThrow().possibleCorporateAction()).isTrue();
        assertThat(r.find("SYNH").orElseThrow().possibleCorporateAction()).isTrue();
        assertThat(r.find("SYNI").orElseThrow().possibleCorporateAction()).isFalse();
        assertThat(r.find("SYNG")).isPresent();
        assertThat(SETTINGS.upGapPct()).isEqualByComparingTo("0.538");
    }

    @Test
    void tooFewBarsBelowMinPriceAndMissing() {
        Map<String, MomentumBarsClient.Series> data = new HashMap<>();
        data.put("SYNJ", new MomentumBarsClient.Series("SYNJ", MomentumBarsClient.Status.OK,
                new BigDecimal("50"), AS_OF, new ArrayList<>(Collections.nCopies(21, BigDecimal.ONE)),
                roc1("0.1", Map.of())));                                                      // 21 < 22 values
        data.put("SYNK", new MomentumBarsClient.Series("SYNK", MomentumBarsClient.Status.INSUFFICIENT,
                new BigDecimal("50"), AS_OF, List.of(), roc1("0.1", Map.of())));
        data.put("SYNL", ok("SYNL", "10", "4.99", roc1("0.1", Map.of())));
        data.put("SYNM", new MomentumBarsClient.Series("SYNM", MomentumBarsClient.Status.NO_DATA,
                null, null, List.of(), List.of()));

        var r = MomentumRanker.rank(List.of("SYNJ", "SYNK", "SYNL", "SYNM", "SYNN"), data, SETTINGS);

        assertThat(r.ranked()).isEmpty();
        assertThat(r.unranked()).containsEntry("SYNJ", MomentumRanker.TOO_FEW_BARS)
                .containsEntry("SYNK", MomentumRanker.TOO_FEW_BARS)
                .containsEntry("SYNL", MomentumRanker.BELOW_MIN_PRICE)
                .containsEntry("SYNM", MomentumRanker.MISSING)
                .containsEntry("SYNN", MomentumRanker.MISSING);
        assertThat(r.count(MomentumRanker.MISSING)).isEqualTo(2);
    }

    @Test
    void oneMonthReturnCompoundsTheLast21DailyReturns() {
        Map<String, MomentumBarsClient.Series> data = Map.of(
                "SYNO", ok("SYNO", "10", "50", roc1("1.00", Map.of(0, "-30.00"))));   // old gap outside the month

        var r = MomentumRanker.rank(List.of("SYNO"), data, SETTINGS);

        assertThat(r.find("SYNO").orElseThrow().return1mPct()).isEqualByComparingTo("23.24");
        assertThat(r.find("SYNO").orElseThrow().worst1dPct()).isEqualByComparingTo("-30.00");
    }
}
