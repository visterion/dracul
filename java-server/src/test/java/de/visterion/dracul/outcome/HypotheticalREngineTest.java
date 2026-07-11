package de.visterion.dracul.outcome;

import de.visterion.dracul.executor.PositionSizer;
import de.visterion.dracul.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HypotheticalREngineTest {

    private final HypotheticalREngine engine = new HypotheticalREngine(new PositionSizer());

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** One bar per trading day starting 2024-01-02, close/high/low derived from a simple offset. */
    private static OhlcBar bar(int dayIndex, String close, String high, String low) {
        return new OhlcBar(LocalDate.of(2024, 1, 1).plusDays(dayIndex), bd(close), bd(high), bd(low), bd(close), 1_000L);
    }

    @Test
    void skipsWhenReferencePriceMissing() {
        HypotheticalOutcome outcome = engine.walk("BUY", null, bd("2"), null, List.of(), 20);
        assertThat(outcome.skippedReason()).isEqualTo("missing reference_price/atr");
        assertThat(outcome.assumedEntry()).isNull();
        assertThat(outcome.rAfter20d()).isNull();
        assertThat(outcome.tripleBarrierLabel()).isNull();
    }

    @Test
    void skipsWhenAtrMissing() {
        HypotheticalOutcome outcome = engine.walk("BUY", bd("100"), null, null, List.of(), 20);
        assertThat(outcome.skippedReason()).isEqualTo("missing reference_price/atr");
    }

    @Test
    void skipsWhenAtrZeroYieldsNonPositiveRPerShare() {
        // atr=0 and no swing low -> stop == entry, rPerShare 0: must skip, never throw on divide
        List<OhlcBar> bars = List.of(bar(1, "100.5", "101", "100"));
        HypotheticalOutcome outcome = engine.walk("BUY", bd("100"), bd("0"), null, bars, 20);
        assertThat(outcome.skippedReason()).isNotNull().contains("non-positive rPerShare");
        assertThat(outcome.rAfter20d()).isNull();
        assertThat(outcome.tripleBarrierLabel()).isNull();
    }

    @Test
    void derivesAssumedEntryAndStopFromAtrOnlyAnchor() {
        // BUY entry 100, ATR 2, no swing low -> stop = 100 - 2.5*2 = 95, rPerShare = 5
        List<OhlcBar> bars = List.of(bar(1, "100.5", "101", "100"));
        HypotheticalOutcome outcome = engine.walk("BUY", bd("100"), bd("2"), null, bars, 20);
        assertThat(outcome.assumedEntry()).isEqualByComparingTo("100");
        assertThat(outcome.assumedStop()).isEqualByComparingTo("95");
        assertThat(outcome.rPerShare()).isEqualByComparingTo("5");
    }

    @Test
    void driftUpTo110By20Days_favorableTouchedFirst_labelTrue() {
        // entry 100, atr 2 -> stop 95, rPerShare 5, +1R target = 105
        // closes drift 100.5 .. 110 over 20 bars; lows never near 95; high crosses 105 on day 9
        List<OhlcBar> bars = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            BigDecimal close = bd("100").add(bd("0.5").multiply(BigDecimal.valueOf(i)));
            BigDecimal high = close.add(bd("0.5"));
            BigDecimal low = close.subtract(bd("0.5"));
            bars.add(bar(i, close.toPlainString(), high.toPlainString(), low.toPlainString()));
        }

        HypotheticalOutcome outcome = engine.walk("BUY", bd("100"), bd("2"), null, bars, 20);

        assertThat(outcome.wouldHaveStoppedOut()).isFalse();
        assertThat(outcome.rAfter20d()).isEqualByComparingTo("2.0"); // (110-100)/5
        assertThat(outcome.rAfter60d()).isNull(); // fewer than 60 bars
        assertThat(outcome.tripleBarrierLabel()).isTrue();
    }

    @Test
    void stopTouchedBeforeFavorable_freezesRAtMinusOne_labelFalse() {
        // entry 100, atr 2 -> stop 95, rPerShare 5. Day 5 low touches 94 (<=95), well before any +1R bar.
        List<OhlcBar> bars = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            if (i == 5) {
                bars.add(bar(i, "94.5", "97", "94")); // stop touched here
            } else {
                bars.add(bar(i, "99", "100", "98")); // never near stop or +1R target otherwise
            }
        }

        HypotheticalOutcome outcome = engine.walk("BUY", bd("100"), bd("2"), null, bars, 20);

        assertThat(outcome.wouldHaveStoppedOut()).isTrue();
        assertThat(outcome.rAfter20d()).isEqualByComparingTo("-1"); // frozen from day 5 onward
        assertThat(outcome.rAfter60d()).isNull(); // fewer than 60 bars
        assertThat(outcome.tripleBarrierLabel()).isFalse();
    }

    @Test
    void ambiguousBarTouchingBothStopAndTarget_isStopFirst() {
        // entry 100, atr 2 -> stop 95, rPerShare 5, +1R target = 105.
        // Single bar whose low <= 95 AND high >= 105 in the same bar -> counted as stopped out.
        List<OhlcBar> bars = List.of(bar(1, "100", "106", "94"));

        HypotheticalOutcome outcome = engine.walk("BUY", bd("100"), bd("2"), null, bars, 20);

        assertThat(outcome.wouldHaveStoppedOut()).isTrue();
        assertThat(outcome.tripleBarrierLabel()).isFalse();
    }

    @Test
    void fewerThan20Bars_ratiosNullAndLabelUndecided() {
        List<OhlcBar> bars = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            bars.add(bar(i, "99", "100", "98")); // never touches stop (95) nor +1R target (105)
        }

        HypotheticalOutcome outcome = engine.walk("BUY", bd("100"), bd("2"), null, bars, 20);

        assertThat(outcome.rAfter20d()).isNull();
        assertThat(outcome.rAfter60d()).isNull();
        assertThat(outcome.wouldHaveStoppedOut()).isFalse();
        assertThat(outcome.tripleBarrierLabel()).isNull(); // undecided: fewer bars than horizon, nothing hit yet
    }

    @Test
    void horizonElapsedWithNeitherTouched_labelFalse() {
        // 20 bars, horizon = 20, nothing ever touches stop or +1R target -> horizon elapsed, label false
        List<OhlcBar> bars = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            bars.add(bar(i, "99", "100", "98"));
        }

        HypotheticalOutcome outcome = engine.walk("BUY", bd("100"), bd("2"), null, bars, 20);

        assertThat(outcome.wouldHaveStoppedOut()).isFalse();
        assertThat(outcome.tripleBarrierLabel()).isFalse();
    }

    @Test
    void rAfter60dPopulatedWhen60BarsAvailable() {
        // entry 100, atr 2 -> stop 95, rPerShare 5. 60 flat bars, close drifts to 115 by day 60.
        List<OhlcBar> bars = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            BigDecimal close = bd("100").add(bd("0.25").multiply(BigDecimal.valueOf(i)));
            bars.add(bar(i, close.toPlainString(), close.add(bd("0.1")).toPlainString(), close.subtract(bd("0.1")).toPlainString()));
        }

        HypotheticalOutcome outcome = engine.walk("BUY", bd("100"), bd("2"), null, bars, 20);

        // day60 close = 100 + 0.25*60 = 115 -> R = (115-100)/5 = 3.0
        assertThat(outcome.rAfter60d()).isEqualByComparingTo("3.0");
    }

    @Test
    void sellSideMirrorsBuy() {
        // SELL entry 100, atr 2 -> stop = 100 + 2.5*2 = 105, rPerShare 5, +1R target = 95 (price falls)
        List<OhlcBar> bars = List.of(bar(1, "94", "96", "93")); // low 93 <= 95 favorable touch, high 96 < 105 stop
        HypotheticalOutcome outcome = engine.walk("SELL", bd("100"), bd("2"), null, bars, 20);

        assertThat(outcome.assumedStop()).isEqualByComparingTo("105");
        assertThat(outcome.wouldHaveStoppedOut()).isFalse();
        assertThat(outcome.tripleBarrierLabel()).isTrue();
    }

    @Test
    void swingLowWidensStopWhenWiderThanAtrAnchor() {
        // BUY entry 100, atr 2 -> atr-only anchor = 95; swingLow 90 is wider -> stop = 90
        HypotheticalOutcome outcome = engine.walk("BUY", bd("100"), bd("2"), bd("90"), List.of(), 20);
        assertThat(outcome.assumedStop()).isEqualByComparingTo("90");
        assertThat(outcome.rPerShare()).isEqualByComparingTo("10");
    }
}
