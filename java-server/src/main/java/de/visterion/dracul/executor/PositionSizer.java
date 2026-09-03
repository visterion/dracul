package de.visterion.dracul.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure position sizer: computes tranche quantity (floored to whole shares), protective stop window,
 * and account-currency risk. Side-aware: BUY uses min/−, SELL uses max/+.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class PositionSizer {

    /**
     * Size a position by tranche amount and price, compute stop window, and convert risk to
     * account currency. All args are instrument-currency except {@code fxToAccount} (multiplier
     * instrument→account) and {@code riskBudgetAccountCcy} (account currency).
     *
     * <p><b>Order of operations matters.</b> Risk-per-share comes from the LOGICAL stop and is
     * checked for positivity BEFORE anything divides by it — a stop collapsed onto the entry is a
     * rejection ({@code NO_R}), never a division. Only then are the two quantity caps computed,
     * and {@code qty} is their minimum.
     *
     * @param side "BUY" or "SELL"
     * @param price entry price (instrument currency)
     * @param atr the ATR the stop window is derived from — the caller passes {@code atrEff}, not
     *        necessarily ATR22
     * @param swingLow recent swing low, nullable (instrument currency); null → use ATR-only baseline
     * @param stopPrice proposed protective LOGICAL stop (instrument currency); never a broker stop
     * @param trancheAmount position size target (instrument currency)
     * @param fxToAccount FX multiplier from instrument to account currency
     * @param riskBudgetAccountCcy maximum loss this tranche may risk, account currency
     * @param atrLabel the name of the ATR window given, for the {@code stopBasis} audit string
     *        (e.g. {@code "ATR22"}, {@code "ATR5"})
     */
    public Sizing size(String side, BigDecimal price, BigDecimal atr, BigDecimal swingLow,
                       BigDecimal stopPrice, BigDecimal trancheAmount, BigDecimal fxToAccount,
                       BigDecimal riskBudgetAccountCcy, String atrLabel) {

        boolean buy = "BUY".equalsIgnoreCase(side);
        BigDecimal rPerShare = buy ? price.subtract(stopPrice) : stopPrice.subtract(price);
        if (rPerShare.signum() <= 0) {
            return zero(Sizing.RejectCause.NO_R);
        }

        BigDecimal qtyNotional = trancheAmount.divide(price, 0, RoundingMode.FLOOR);
        if (qtyNotional.signum() == 0) {
            return zero(Sizing.RejectCause.NOTIONAL_ZERO);
        }

        BigDecimal riskPerShareAccountCcy = rPerShare.multiply(fxToAccount);
        BigDecimal qtyRisk = riskBudgetAccountCcy.divide(riskPerShareAccountCcy, 0, RoundingMode.FLOOR);
        if (qtyRisk.signum() == 0) {
            // Unlike the other two reject paths, qtyNotional here is a real, non-zero, already
            // computed value (rPerShare and qtyNotional both passed their own checks) — the audit
            // trail should show what the notional cap alone would have allowed, not a blanket zero.
            return new Sizing(BigDecimal.ZERO, rPerShare, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, false, null, qtyNotional, qtyRisk, null,
                    Sizing.RejectCause.RISK_ZERO);
        }

        BigDecimal qty = qtyNotional.min(qtyRisk);
        // Ties go to NOTIONAL: it is the pre-existing rule, and a report that says "RISK" when
        // both caps agree would overstate how often the new cap actually bit.
        String sizingBasis = qtyRisk.compareTo(qtyNotional) < 0 ? "RISK" : "NOTIONAL";

        StopWindow w = stopWindow(side, price, atr, swingLow, atrLabel);
        boolean stopInWindow = stopPrice.compareTo(w.stopMin()) >= 0
                && stopPrice.compareTo(w.stopMax()) <= 0;

        BigDecimal newRiskAccountCcy = qty.multiply(rPerShare).multiply(fxToAccount)
                .setScale(4, RoundingMode.HALF_UP);

        return new Sizing(qty, rPerShare, newRiskAccountCcy, w.stopMin(), w.stopMax(), stopInWindow,
                w.stopBasis(), qtyNotional, qtyRisk, sizingBasis, null);
    }

    /** The zero-quantity shape for the NO_R and NOTIONAL_ZERO reject paths, where no other field
     *  has a real computed value yet (NO_R: nothing past rPerShare was computed; NOTIONAL_ZERO:
     *  qtyNotional genuinely is zero). RISK_ZERO does NOT use this — it has a real, non-zero
     *  qtyNotional to report, built inline instead. */
    private static Sizing zero(Sizing.RejectCause cause) {
        return new Sizing(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                cause);
    }

    /**
     * Back-compat overload for the callers that use the window bounds only and discard
     * {@link StopWindow#stopBasis()} (the entry-path window at
     * {@code ExecutorWebhookController:645}, the LLM window at {@code :309}, and
     * {@link StopWindowRounding#compute}). {@code "ATR22"} keeps the pre-SP1 string for anyone who
     * does read it. The only consumer that MUST get a truthful label is {@link #size}, which
     * always passes one.
     */
    public StopWindow stopWindow(String side, BigDecimal price, BigDecimal atr, BigDecimal swingLow) {
        return stopWindow(side, price, atr, swingLow, "ATR22");
    }

    /**
     * Computes the protective-stop window (min/max/basis) for a side. Extracted from {@link #size}
     * so the executor's risk layer can evaluate the window independently of a full sizing call.
     *
     * @param side "BUY" or "SELL"
     * @param price entry price (instrument currency)
     * @param atr average true range (instrument currency)
     * @param swingLow recent swing low, nullable (instrument currency); null → use ATR-only baseline
     * @param atrLabel the name of the ATR window given, for the {@code stopBasis} audit string
     * @return the stop window; {@code stopMin <= stopMax}
     */
    public StopWindow stopWindow(String side, BigDecimal price, BigDecimal atr, BigDecimal swingLow,
            String atrLabel) {
        BigDecimal stopMin, stopMax;
        String stopBasis;

        if ("BUY".equalsIgnoreCase(side)) {
            // BUY: anchor = min(price - 2.5×atr, swingLow), floor uses min and -
            BigDecimal atrThree = atr.multiply(BigDecimal.valueOf(3));
            BigDecimal atrQuarter = atr.multiply(new BigDecimal("0.25"));

            BigDecimal atrOnlyAnchor = deriveStopAnchor(side, price, atr, null);
            BigDecimal anchor = deriveStopAnchor(side, price, atr, swingLow);
            boolean swingLowWins = anchor.compareTo(atrOnlyAnchor) != 0;
            stopBasis = swingLowWins
                    ? "swing_low " + plain(swingLow) + " (wider than entry - 2.5 x " + atrLabel
                            + " " + plain(atrOnlyAnchor) + ")"
                    : "entry - 2.5 x " + atrLabel;

            BigDecimal buyFloorBase = price.subtract(atrThree);
            BigDecimal floorBeforeAdjust = swingLow != null ? min(buyFloorBase, swingLow) : buyFloorBase;
            BigDecimal floor = floorBeforeAdjust.subtract(atrQuarter);

            stopMax = anchor;
            stopMin = floor;

        } else {
            // SELL: anchor = max(price + 2.5×atr, swingLow), floor uses max and +
            BigDecimal atrThree = atr.multiply(BigDecimal.valueOf(3));
            BigDecimal atrQuarter = atr.multiply(new BigDecimal("0.25"));

            BigDecimal atrOnlyAnchor = deriveStopAnchor(side, price, atr, null);
            BigDecimal anchor = deriveStopAnchor(side, price, atr, swingLow);
            boolean swingLowWins = anchor.compareTo(atrOnlyAnchor) != 0;
            stopBasis = swingLowWins
                    ? "swing_low " + plain(swingLow) + " (wider than entry + 2.5 x " + atrLabel
                            + " " + plain(atrOnlyAnchor) + ")"
                    : "entry + 2.5 x " + atrLabel;

            BigDecimal sellFloorBase = price.add(atrThree);
            BigDecimal floorBeforeAdjust = swingLow != null ? max(sellFloorBase, swingLow) : sellFloorBase;
            BigDecimal floor = floorBeforeAdjust.add(atrQuarter);

            stopMin = anchor;  // For SELL, we swap min/max in the output
            stopMax = floor;
        }

        return new StopWindow(stopMin, stopMax, stopBasis);
    }

    /**
     * Derives the protective-stop anchor (single source of truth, also used by
     * {@link de.visterion.dracul.outcome.HypotheticalREngine} to walk hypothetical price paths
     * without needing the full {@link #size} call, which requires a tranche amount / FX rate
     * irrelevant to a stop-only computation).
     *
     * <p>BUY: {@code min(price - 2.5*atr, swingLow)}. SELL: {@code max(price + 2.5*atr, swingLow)}.
     * {@code swingLow} may be null, in which case the ATR-only anchor is used.
     *
     * @param side "BUY" or "SELL"
     * @param price entry/reference price (instrument currency)
     * @param atr average true range (instrument currency)
     * @param swingLow recent swing low, nullable (instrument currency)
     * @return the anchor stop price (instrument currency)
     */
    public static BigDecimal deriveStopAnchor(String side, BigDecimal price, BigDecimal atr, BigDecimal swingLow) {
        BigDecimal atrTwoHalf = atr.multiply(new BigDecimal("2.5"));
        if ("BUY".equalsIgnoreCase(side)) {
            BigDecimal buyAnchor = price.subtract(atrTwoHalf);
            return (swingLow != null && swingLow.compareTo(buyAnchor) < 0) ? swingLow : buyAnchor;
        } else {
            BigDecimal sellAnchor = price.add(atrTwoHalf);
            return (swingLow != null && swingLow.compareTo(sellAnchor) > 0) ? swingLow : sellAnchor;
        }
    }

    private BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) < 0 ? a : b;
    }

    private BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0 ? a : b;
    }

    /** Trailing-zero-free plain string for stop-basis audit text (e.g. {@code 38.10} not
     *  {@code 38.100000}). */
    private String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
