package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PositionSizerTest {

    private final PositionSizer sizer = new PositionSizer();

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** A risk budget so large the risk cap can never bind — lets the pre-SP1 tests below keep
     *  exercising exactly the notional path they were written for. */
    private static final BigDecimal NO_RISK_CAP = new BigDecimal("1000000000");

    /** Legacy-shaped call: notional sizing only, ATR22 label. */
    private Sizing size(String side, BigDecimal price, BigDecimal atr, BigDecimal swingLow,
            BigDecimal stopPrice, BigDecimal trancheAmount, BigDecimal fxToAccount) {
        return sizer.size(side, price, atr, swingLow, stopPrice, trancheAmount, fxToAccount,
                NO_RISK_CAP, "ATR22");
    }

    @Test
    void computesQtyFloor() { // tranche 1000, price 137.64 -> qty 7
        Sizing s = size("BUY", bd("137.64"), bd("3.0"), null, bd("130.0"), bd("1000"), BigDecimal.ONE);
        assertThat(s.qty()).isEqualByComparingTo("7");
    }

    @Test
    void qtyZeroWhenPriceExceedsTranche() { // price 1200 > tranche 1000
        assertThat(size("BUY", bd("1200"), bd("10"), null, bd("1150"), bd("1000"), BigDecimal.ONE).qty())
                .isEqualByComparingTo("0");
    }

    @Test
    void stopWindowAtrOnly() { // price 100, atr 4: window [87.0 (=100-12-1), 90.0]
        Sizing s = size("BUY", bd("100"), bd("4"), null, bd("89.0"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopMax()).isEqualByComparingTo("90.0");   // 100 - 2.5*4
        assertThat(s.stopMin()).isEqualByComparingTo("87.0");   // (100 - 3*4) - 0.25*4
        assertThat(s.stopInWindow()).isTrue();
    }

    @Test
    void stopTighterThanAnchorRejected() { // stop 92 > anchor 90
        assertThat(size("BUY", bd("100"), bd("4"), null, bd("92"), bd("1000"), BigDecimal.ONE).stopInWindow()).isFalse();
    }

    @Test
    void swingLowWidensWindow() { // swingLow 85 < 100-2.5*4=90 -> anchor 85, floor min(88,85)-1=84
        Sizing s = size("BUY", bd("100"), bd("4"), bd("85"), bd("84.5"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopMax()).isEqualByComparingTo("85");
        assertThat(s.stopMin()).isEqualByComparingTo("84.0");
        assertThat(s.stopInWindow()).isTrue();
    }

    @Test
    void riskConvertsToAccountCcy() { // qty 7 * r 7.64 * fx 0.92
        Sizing s = size("BUY", bd("137.64"), bd("3.0"), null, bd("130.0"), bd("1000"), bd("0.92"));
        assertThat(s.newRiskAccountCcy()).isEqualByComparingTo("49.2016"); // 7*7.64*0.92
    }

    @Test
    void sellMirrors() { // price 100, atr 4, stop must be ABOVE: window [110.0, 113.0]
        Sizing s = size("SELL", bd("100"), bd("4"), null, bd("111"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopInWindow()).isTrue();
        assertThat(s.rPerShare()).isEqualByComparingTo("11");
    }

    // ---- stopBasis: which anchor won (ATR-only baseline vs a wider swing-low) ----

    @Test
    void stopBasisIsAtrWhenNoSwingLow() { // no swingLow -> ATR-only anchor always wins
        Sizing s = size("BUY", bd("100"), bd("4"), null, bd("89.0"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopBasis()).contains("ATR");
    }

    @Test
    void stopBasisIsAtrWhenAtrAnchorWiderThanSwingLow() { // swingLow 88 is TIGHTER than ATR anchor 90 -> ATR wins
        Sizing s = size("BUY", bd("100"), bd("4"), bd("88"), bd("89.0"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopBasis()).contains("ATR");
    }

    @Test
    void stopBasisIsSwingLowWhenWiderThanAtrAnchor() { // swingLow 85 < ATR anchor 90 -> swing_low wins
        Sizing s = size("BUY", bd("100"), bd("4"), bd("85"), bd("84.5"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopBasis()).contains("swing_low");
    }

    @Test
    void stopBasisSellMirror_atrWinsWhenNoSwingLow() {
        Sizing s = size("SELL", bd("100"), bd("4"), null, bd("111"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopBasis()).contains("ATR");
    }

    @Test
    void stopBasisSellMirror_swingLowWinsWhenWider() { // sellAnchor=110; swingLow 115 > 110 -> swing_low wins
        Sizing s = size("SELL", bd("100"), bd("4"), bd("115"), bd("116"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopBasis()).contains("swing_low");
    }

    @Test
    void stopBasisNullWhenQtyZero() {
        Sizing s = size("BUY", bd("1200"), bd("10"), null, bd("1150"), bd("1000"), BigDecimal.ONE);
        assertThat(s.stopBasis()).isNull();
    }

    // ---- stopWindow(...): must match the window size(...) produces ----

    @Test
    void stopWindowMatchesSizeBoundsBuy() {
        var price = bd("100"); var atr = bd("2"); var swing = bd("95");
        StopWindow w = sizer.stopWindow("BUY", price, atr, swing);
        Sizing s = size("BUY", price, atr, swing, bd("96"), bd("1000"), BigDecimal.ONE);
        assertThat(w.stopMin()).isEqualByComparingTo(s.stopMin());
        assertThat(w.stopMax()).isEqualByComparingTo(s.stopMax());
        assertThat(w.stopMin()).isLessThanOrEqualTo(w.stopMax());
    }

    @Test
    void stopWindowMatchesSizeBoundsSell() {
        var price = bd("100"); var atr = bd("2"); var swing = bd("105");
        StopWindow w = sizer.stopWindow("SELL", price, atr, swing);
        Sizing s = size("SELL", price, atr, swing, bd("104"), bd("1000"), BigDecimal.ONE);
        assertThat(w.stopMin()).isEqualByComparingTo(s.stopMin());
        assertThat(w.stopMax()).isEqualByComparingTo(s.stopMax());
        assertThat(w.stopMin()).isLessThanOrEqualTo(w.stopMax());
    }

    /** Test 1. price 100, tranche 1000 -> qtyNotional 10; stop 95 -> r/share 5, fx 1, risk budget
     *  100 -> qtyRisk 20... too loose. Use risk budget 30 -> qtyRisk = floor(30/5) = 6 < 10.
     *  Mutation: use qtyNotional instead of min(qtyNotional, qtyRisk). */
    @Test
    void riskCapBindsWhenTighterThanNotional() {
        Sizing s = sizer.size("BUY", bd("100"), bd("4"), null, bd("95"), bd("1000"),
                BigDecimal.ONE, bd("30"), "ATR22");

        assertThat(s.qtyNotional()).isEqualByComparingTo("10");
        assertThat(s.qtyRisk()).isEqualByComparingTo("6");
        assertThat(s.qty()).isEqualByComparingTo("6");
        assertThat(s.sizingBasis()).isEqualTo("RISK");
        assertThat(s.rejectCause()).isNull();
        // position_risk follows the qty actually used: 6 * 5 * 1
        assertThat(s.newRiskAccountCcy()).isEqualByComparingTo("30.0000");
    }

    /** Test 2. Same shape, generous risk budget: qtyRisk 100 > qtyNotional 10.
     *  Mutation: use qtyRisk instead of the min. */
    @Test
    void notionalCapBindsWhenTighterThanRisk() {
        Sizing s = sizer.size("BUY", bd("100"), bd("4"), null, bd("95"), bd("1000"),
                BigDecimal.ONE, bd("500"), "ATR22");

        assertThat(s.qtyNotional()).isEqualByComparingTo("10");
        assertThat(s.qtyRisk()).isEqualByComparingTo("100");
        assertThat(s.qty()).isEqualByComparingTo("10");
        assertThat(s.sizingBasis()).isEqualTo("NOTIONAL");
        assertThat(s.rejectCause()).isNull();
    }

    /** Test 4. atr 0 collapses the window onto the price, and a stop AT the price gives a zero
     *  risk-per-share. The sizer must reject with NO_R BEFORE dividing by it.
     *  Mutation: compute qtyRisk before the guard -> ArithmeticException / division by zero. */
    @Test
    void zeroRPerShareIsRejectedNotDivided() {
        Sizing s = sizer.size("BUY", bd("100"), bd("0"), null, bd("100"), bd("1000"),
                BigDecimal.ONE, bd("100"), "ATR22");

        assertThat(s.rejectCause()).isEqualTo(Sizing.RejectCause.NO_R);
        assertThat(s.qty()).isEqualByComparingTo("0");
        assertThat(s.sizingBasis()).isNull();
    }

    /** Test 4. A stop on the WRONG side of the entry is also a non-positive rPerShare. */
    @Test
    void negativeRPerShareIsRejectedAsNoR() {
        Sizing s = sizer.size("BUY", bd("100"), bd("4"), null, bd("105"), bd("1000"),
                BigDecimal.ONE, bd("100"), "ATR22");

        assertThat(s.rejectCause()).isEqualTo(Sizing.RejectCause.NO_R);
        assertThat(s.qty()).isEqualByComparingTo("0");
    }

    /** A price above the whole tranche is still NOTIONAL_ZERO, checked BEFORE the risk cap — that
     *  ordering is what preserves today's TRANCHE_TOO_SMALL outcome for expensive instruments.
     *  Mutation: check the risk cap first, and this becomes RISK_ZERO. */
    @Test
    void priceAboveTheTrancheIsNotionalZeroNotRiskZero() {
        // price 1200 > tranche 1000 -> qtyNotional 0. rPerShare 50, budget 30 would ALSO floor
        // qtyRisk to 0, so only the ordering decides which cause is reported.
        Sizing s = sizer.size("BUY", bd("1200"), bd("20"), null, bd("1150"), bd("1000"),
                BigDecimal.ONE, bd("30"), "ATR22");

        assertThat(s.qtyNotional()).isEqualByComparingTo("0");
        assertThat(s.rejectCause()).isEqualTo(Sizing.RejectCause.NOTIONAL_ZERO);
    }

    /** Test 4b. Stop distance x fx exceeds the whole risk budget -> qtyRisk floors to 0. This must
     *  be its OWN cause, not folded into NOTIONAL_ZERO: the controller routes the two to different
     *  reject reasons (RISK_TOO_WIDE vs TRANCHE_TOO_SMALL).
     *  Mutation: return NOTIONAL_ZERO here, or reuse a single qty == 0 branch. */
    @Test
    void riskCapFlooringToZeroYieldsRiskZeroCause() {
        // price 40, tranche 1000 -> qtyNotional 25. r/share 32 (stop 8), risk budget 30 ->
        // floor(30/32) = 0.
        Sizing s = sizer.size("BUY", bd("40"), bd("16"), null, bd("8"), bd("1000"),
                BigDecimal.ONE, bd("30"), "ATR22");

        assertThat(s.qtyNotional()).isEqualByComparingTo("25");
        assertThat(s.qtyRisk()).isEqualByComparingTo("0");
        assertThat(s.rejectCause()).isEqualTo(Sizing.RejectCause.RISK_ZERO);
        assertThat(s.qty()).isEqualByComparingTo("0");
    }

    /** Test 5. SELL mirrors: rPerShare is stop - price, and the risk cap works off that.
     *  Mutation: use the BUY formula (price - stop) on SELL -> a negative rPerShare and a
     *  spurious NO_R. */
    @Test
    void sellSideRiskCapMirrors() {
        // SELL price 100, stop 105 -> r/share 5. Risk budget 30 -> qtyRisk 6, qtyNotional 10.
        Sizing s = sizer.size("SELL", bd("100"), bd("4"), null, bd("105"), bd("1000"),
                BigDecimal.ONE, bd("30"), "ATR22");

        assertThat(s.rPerShare()).isEqualByComparingTo("5");
        assertThat(s.qtyRisk()).isEqualByComparingTo("6");
        assertThat(s.qty()).isEqualByComparingTo("6");
        assertThat(s.sizingBasis()).isEqualTo("RISK");
    }

    /** Test 5. The fx multiplier is part of the risk quotient: the budget is in ACCOUNT currency,
     *  rPerShare in instrument currency. Mutation: drop fxToAccount from the divisor. */
    @Test
    void riskCapConvertsRiskPerShareToAccountCurrency() {
        // r/share 5 instrument ccy, fx 0.5 -> 2.5 account ccy per share; budget 30 -> qtyRisk 12.
        Sizing s = sizer.size("BUY", bd("100"), bd("4"), null, bd("95"), bd("1000"),
                bd("0.5"), bd("30"), "ATR22");

        assertThat(s.qtyRisk()).isEqualByComparingTo("12");
        assertThat(s.qty()).isEqualByComparingTo("10"); // notional still binds
        assertThat(s.sizingBasis()).isEqualTo("NOTIONAL");
    }

    /** Test 7. Six pre-SP1-shaped inputs (synthetic values) with a risk budget large enough that
     *  the cap never binds must reproduce the legacy outputs exactly: qty, rPerShare,
     *  newRiskAccountCcy, stopMin, stopMax, stopInWindow, stopBasis.
     *  Mutation: apply the risk cap unconditionally (qty = qtyRisk) — every row below then sizes
     *  to a different quantity. */
    @Test
    void legacyInputsProduceIdenticalSizingWhenRiskCapNeverBinds() {
        record Case(String side, String price, String atr, String swingLow, String stop,
                String tranche, String fx, String qty, String rPerShare, String risk,
                String stopMin, String stopMax, boolean inWindow, String basis) { }

        List<Case> cases = List.of(
                new Case("BUY", "100", "4", null, "95", "1000", "1",
                        "10", "5", "50.0000", "87.0", "90.0", false, "entry - 2.5 x ATR22"),
                new Case("BUY", "137.64", "3.0", null, "130.0", "1000", "0.92",
                        "7", "7.64", "49.2016", "127.89", "130.14", true, "entry - 2.5 x ATR22"),
                new Case("BUY", "100", "4", "85", "84.5", "1000", "1",
                        "10", "15.5", "155.0000", "84.0", "85", true,
                        "swing_low 85 (wider than entry - 2.5 x ATR22 90)"),
                new Case("SELL", "100", "4", null, "111", "1000", "1",
                        "10", "11", "110.0000", "110.0", "113.0", true, "entry + 2.5 x ATR22"),
                new Case("SELL", "50", "2", "56", "56", "1000", "1",
                        "20", "6", "120.0000", "56", "56.5", true,
                        "swing_low 56 (wider than entry + 2.5 x ATR22 55)"),
                new Case("BUY", "20.5", "0.5", null, "19.4", "1000", "1",
                        "48", "1.1", "52.8000", "18.875", "19.25", false, "entry - 2.5 x ATR22"));

        for (Case c : cases) {
            Sizing s = sizer.size(c.side(), bd(c.price()), bd(c.atr()),
                    c.swingLow() == null ? null : bd(c.swingLow()), bd(c.stop()), bd(c.tranche()),
                    bd(c.fx()), NO_RISK_CAP, "ATR22");

            assertThat(s.qty()).as("qty for " + c).isEqualByComparingTo(c.qty());
            assertThat(s.rPerShare()).as("rPerShare for " + c).isEqualByComparingTo(c.rPerShare());
            assertThat(s.newRiskAccountCcy()).as("risk for " + c).isEqualByComparingTo(c.risk());
            assertThat(s.stopMin()).as("stopMin for " + c).isEqualByComparingTo(c.stopMin());
            assertThat(s.stopMax()).as("stopMax for " + c).isEqualByComparingTo(c.stopMax());
            assertThat(s.stopInWindow()).as("stopInWindow for " + c).isEqualTo(c.inWindow());
            assertThat(s.stopBasis()).as("stopBasis for " + c).isEqualTo(c.basis());
            assertThat(s.sizingBasis()).as("sizingBasis for " + c).isEqualTo("NOTIONAL");
            assertThat(s.rejectCause()).as("rejectCause for " + c).isNull();
        }
    }

    /** The stop-basis audit string must name the ATR that was actually used, not a hard-coded
     *  "ATR22". Mutation: keep the literal. (The controller half of this is test 34d.) */
    @Test
    void stopBasisNamesTheAtrLabelItWasGiven() {
        Sizing s = sizer.size("BUY", bd("100"), bd("4"), null, bd("89"), bd("1000"),
                BigDecimal.ONE, NO_RISK_CAP, "ATR5");
        assertThat(s.stopBasis()).isEqualTo("entry - 2.5 x ATR5");

        Sizing sell = sizer.size("SELL", bd("100"), bd("4"), null, bd("111"), bd("1000"),
                BigDecimal.ONE, NO_RISK_CAP, "ATR5");
        assertThat(sell.stopBasis()).isEqualTo("entry + 2.5 x ATR5");

        Sizing swing = sizer.size("BUY", bd("100"), bd("4"), bd("85"), bd("84.5"), bd("1000"),
                BigDecimal.ONE, NO_RISK_CAP, "ATR5");
        assertThat(swing.stopBasis()).isEqualTo("swing_low 85 (wider than entry - 2.5 x ATR5 90)");
    }
}
