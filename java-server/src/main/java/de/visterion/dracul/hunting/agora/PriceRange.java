package de.visterion.dracul.hunting.agora;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Last daily close plus the 52-week high/low of one symbol, as returned by ONE Agora
 * {@code get_indicators} call. {@code low52} is guaranteed positive and {@code currentClose}
 * non-null — {@link AgoraPriceRange} returns null instead of constructing a half-empty range.
 */
public record PriceRange(String symbol, BigDecimal currentClose, BigDecimal low52, BigDecimal high52) {

    /** Fraction above the 52-week low, e.g. 0.10 for "10 % above the low". */
    public double pctAboveLow() {
        return currentClose.subtract(low52).divide(low52, MathContext.DECIMAL64).doubleValue();
    }
}
