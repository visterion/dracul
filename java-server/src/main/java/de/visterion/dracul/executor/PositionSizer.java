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
     * Size a position by tranche amount and price, compute stop window, and convert risk to account currency.
     * All args are instrument-currency except fxToAccount (multiplier instrument→account, defaults to 1).
     *
     * @param side "BUY" or "SELL"
     * @param price entry price (instrument currency)
     * @param atr average true range (instrument currency)
     * @param swingLow recent swing low, nullable (instrument currency); null → use ATR-only baseline
     * @param stopPrice proposed protective stop (instrument currency)
     * @param trancheAmount position size target (instrument currency)
     * @param fxToAccount FX multiplier from instrument to account currency (defaults to 1)
     * @return Sizing with qty (floored whole shares; 0 if price exceeds tranche),
     *         stop window (anchor/floor for BUY/SELL), stopInWindow flag, and account-ccy risk
     */
    public Sizing size(String side, BigDecimal price, BigDecimal atr, BigDecimal swingLow,
                       BigDecimal stopPrice, BigDecimal trancheAmount, BigDecimal fxToAccount) {

        // Compute quantity: floor to whole shares
        BigDecimal qty = trancheAmount.divide(price, 0, RoundingMode.FLOOR);

        // If qty < 1, return with qty=ZERO (controller will reject as DATA_UNAVAILABLE/TRANCHE_TOO_SMALL)
        if (qty.signum() == 0) {
            return new Sizing(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    false,
                    null
            );
        }

        // Compute stop window based on side
        BigDecimal stopMin, stopMax;
        BigDecimal rPerShare;
        boolean stopInWindow;
        String stopBasis;

        if ("BUY".equalsIgnoreCase(side)) {
            // BUY: anchor = min(price - 2.5×atr, swingLow), floor uses min and -
            BigDecimal atrThree = atr.multiply(BigDecimal.valueOf(3));
            BigDecimal atrQuarter = atr.multiply(new BigDecimal("0.25"));

            BigDecimal atrOnlyAnchor = deriveStopAnchor(side, price, atr, null);
            BigDecimal anchor = deriveStopAnchor(side, price, atr, swingLow);
            boolean swingLowWins = anchor.compareTo(atrOnlyAnchor) != 0;
            stopBasis = swingLowWins
                    ? "swing_low " + plain(swingLow) + " (wider than entry - 2.5 x ATR22 " + plain(atrOnlyAnchor) + ")"
                    : "entry - 2.5 x ATR22";

            BigDecimal buyFloorBase = price.subtract(atrThree);
            BigDecimal floorBeforeAdjust = swingLow != null ? min(buyFloorBase, swingLow) : buyFloorBase;
            BigDecimal floor = floorBeforeAdjust.subtract(atrQuarter);

            stopMax = anchor;
            stopMin = floor;
            rPerShare = price.subtract(stopPrice);
            stopInWindow = stopPrice.compareTo(floor) >= 0 && stopPrice.compareTo(anchor) <= 0;

        } else {
            // SELL: anchor = max(price + 2.5×atr, swingLow), floor uses max and +
            BigDecimal atrThree = atr.multiply(BigDecimal.valueOf(3));
            BigDecimal atrQuarter = atr.multiply(new BigDecimal("0.25"));

            BigDecimal atrOnlyAnchor = deriveStopAnchor(side, price, atr, null);
            BigDecimal anchor = deriveStopAnchor(side, price, atr, swingLow);
            boolean swingLowWins = anchor.compareTo(atrOnlyAnchor) != 0;
            stopBasis = swingLowWins
                    ? "swing_low " + plain(swingLow) + " (wider than entry + 2.5 x ATR22 " + plain(atrOnlyAnchor) + ")"
                    : "entry + 2.5 x ATR22";

            BigDecimal sellFloorBase = price.add(atrThree);
            BigDecimal floorBeforeAdjust = swingLow != null ? max(sellFloorBase, swingLow) : sellFloorBase;
            BigDecimal floor = floorBeforeAdjust.add(atrQuarter);

            stopMin = anchor;  // For SELL, we swap min/max in the output
            stopMax = floor;
            rPerShare = stopPrice.subtract(price);
            stopInWindow = stopPrice.compareTo(anchor) >= 0 && stopPrice.compareTo(floor) <= 0;
        }

        // Risk calculation: qty × rPerShare × fxToAccount, scaled to 4 decimals with HALF_UP rounding
        BigDecimal newRiskAccountCcy = qty.multiply(rPerShare).multiply(fxToAccount)
                .setScale(4, RoundingMode.HALF_UP);

        return new Sizing(qty, rPerShare, newRiskAccountCcy, stopMin, stopMax, stopInWindow, stopBasis);
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
