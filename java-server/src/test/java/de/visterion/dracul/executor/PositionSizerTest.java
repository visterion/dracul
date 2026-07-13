package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PositionSizerTest {

    private final PositionSizer sizer = new PositionSizer();

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void computesQtyFloor() { // tranche 1000, price 137.64 -> qty 7
        Sizing s = sizer.size("BUY", bd("137.64"), bd("3.0"), null, bd("130.0"), bd("1000"), BigDecimal.ONE);
        assertThat(s.qty()).isEqualByComparingTo("7");
    }

    @Test
    void qtyZeroWhenPriceExceedsTranche() { // price 1200 > tranche 1000
        assertThat(sizer.size("BUY", bd("1200"), bd("10"), null, bd("1150"), bd("1000"), BigDecimal.ONE).qty())
                .isEqualByComparingTo("0");
    }

    @Test
    void stopWindowAtrOnly() { // price 100, atr 4: window [87.0 (=100-12-1), 90.0]
        Sizing s = sizer.size("BUY", bd("100"), bd("4"), null, bd("89.0"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopMax()).isEqualByComparingTo("90.0");   // 100 - 2.5*4
        assertThat(s.stopMin()).isEqualByComparingTo("87.0");   // (100 - 3*4) - 0.25*4
        assertThat(s.stopInWindow()).isTrue();
    }

    @Test
    void stopTighterThanAnchorRejected() { // stop 92 > anchor 90
        assertThat(sizer.size("BUY", bd("100"), bd("4"), null, bd("92"), bd("1000"), BigDecimal.ONE).stopInWindow()).isFalse();
    }

    @Test
    void swingLowWidensWindow() { // swingLow 85 < 100-2.5*4=90 -> anchor 85, floor min(88,85)-1=84
        Sizing s = sizer.size("BUY", bd("100"), bd("4"), bd("85"), bd("84.5"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopMax()).isEqualByComparingTo("85");
        assertThat(s.stopMin()).isEqualByComparingTo("84.0");
        assertThat(s.stopInWindow()).isTrue();
    }

    @Test
    void riskConvertsToAccountCcy() { // qty 7 * r 7.64 * fx 0.92
        Sizing s = sizer.size("BUY", bd("137.64"), bd("3.0"), null, bd("130.0"), bd("1000"), bd("0.92"));
        assertThat(s.newRiskAccountCcy()).isEqualByComparingTo("49.2016"); // 7*7.64*0.92
    }

    @Test
    void sellMirrors() { // price 100, atr 4, stop must be ABOVE: window [110.0, 113.0]
        Sizing s = sizer.size("SELL", bd("100"), bd("4"), null, bd("111"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopInWindow()).isTrue();
        assertThat(s.rPerShare()).isEqualByComparingTo("11");
    }

    // ---- stopBasis: which anchor won (ATR-only baseline vs a wider swing-low) ----

    @Test
    void stopBasisIsAtrWhenNoSwingLow() { // no swingLow -> ATR-only anchor always wins
        Sizing s = sizer.size("BUY", bd("100"), bd("4"), null, bd("89.0"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopBasis()).contains("ATR");
    }

    @Test
    void stopBasisIsAtrWhenAtrAnchorWiderThanSwingLow() { // swingLow 88 is TIGHTER than ATR anchor 90 -> ATR wins
        Sizing s = sizer.size("BUY", bd("100"), bd("4"), bd("88"), bd("89.0"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopBasis()).contains("ATR");
    }

    @Test
    void stopBasisIsSwingLowWhenWiderThanAtrAnchor() { // swingLow 85 < ATR anchor 90 -> swing_low wins
        Sizing s = sizer.size("BUY", bd("100"), bd("4"), bd("85"), bd("84.5"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopBasis()).contains("swing_low");
    }

    @Test
    void stopBasisSellMirror_atrWinsWhenNoSwingLow() {
        Sizing s = sizer.size("SELL", bd("100"), bd("4"), null, bd("111"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopBasis()).contains("ATR");
    }

    @Test
    void stopBasisSellMirror_swingLowWinsWhenWider() { // sellAnchor=110; swingLow 115 > 110 -> swing_low wins
        Sizing s = sizer.size("SELL", bd("100"), bd("4"), bd("115"), bd("116"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopBasis()).contains("swing_low");
    }

    @Test
    void stopBasisNullWhenQtyZero() {
        Sizing s = sizer.size("BUY", bd("1200"), bd("10"), null, bd("1150"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopBasis()).isNull();
    }

    // ---- stopWindow(...): must match the window size(...) produces ----

    @Test
    void stopWindowMatchesSizeBoundsBuy() {
        var price = bd("100"); var atr = bd("2"); var swing = bd("95");
        StopWindow w = sizer.stopWindow("BUY", price, atr, swing);
        Sizing s = sizer.size("BUY", price, atr, swing, bd("96"), bd("1000"), BigDecimal.ONE);
        assertThat(w.stopMin()).isEqualByComparingTo(s.stopMin());
        assertThat(w.stopMax()).isEqualByComparingTo(s.stopMax());
        assertThat(w.stopMin()).isLessThanOrEqualTo(w.stopMax());
    }

    @Test
    void stopWindowMatchesSizeBoundsSell() {
        var price = bd("100"); var atr = bd("2"); var swing = bd("105");
        StopWindow w = sizer.stopWindow("SELL", price, atr, swing);
        Sizing s = sizer.size("SELL", price, atr, swing, bd("104"), bd("1000"), BigDecimal.ONE);
        assertThat(w.stopMin()).isEqualByComparingTo(s.stopMin());
        assertThat(w.stopMax()).isEqualByComparingTo(s.stopMax());
        assertThat(w.stopMin()).isLessThanOrEqualTo(w.stopMax());
    }
}
