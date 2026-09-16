package de.visterion.dracul.hunting.agora;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Last completed daily close plus the 52-week high/low of one symbol, as returned by ONE Agora
 * {@code get_indicators} call. {@code low52} is guaranteed positive and {@code lastClose}
 * non-null — {@link AgoraPriceRange} returns null instead of constructing a half-empty range.
 *
 * <p>{@code lastClose} is the close of the last COMPLETED daily bar (SP8): Agora's 52-week window
 * is computed over completed bars only, and comparing a live intraday print against a window that
 * no longer contains it would let {@link #pctAboveLow()} go negative on a fresh intraday low. One
 * vintage on both sides keeps the ratio well-defined and stable within a session. While an
 * exchange is open this is therefore NOT the live price.
 */
public record PriceRange(String symbol, BigDecimal lastClose, BigDecimal low52, BigDecimal high52) {

    /** Fraction above the 52-week low, e.g. 0.10 for "10 % above the low". */
    public double pctAboveLow() {
        return lastClose.subtract(low52).divide(low52, MathContext.DECIMAL64).doubleValue();
    }
}
