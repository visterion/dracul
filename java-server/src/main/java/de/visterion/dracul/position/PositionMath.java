package de.visterion.dracul.position;

import java.math.BigDecimal;
import java.math.MathContext;

/** Shared direction/P&L arithmetic for position facts (T2.2). Deterministic, null-safe. */
public final class PositionMath {

    private PositionMath() {}

    /** "long" for positive quantity, "short" for negative, null for null/zero (status quo). */
    public static String direction(BigDecimal quantity) {
        if (quantity == null || quantity.signum() == 0) return null;
        return quantity.signum() < 0 ? "short" : "long";
    }

    /** Sign-correct P&L percent vs entry: long/null-direction = (close-entry)/entry*100,
     *  short = (entry-close)/entry*100. Null when entry/close missing or entry zero. */
    public static BigDecimal gainLossPct(String direction, BigDecimal entry, BigDecimal close) {
        if (entry == null || close == null || entry.signum() == 0) return null;
        BigDecimal diff = "short".equals(direction) ? entry.subtract(close) : close.subtract(entry);
        return diff.divide(entry, MathContext.DECIMAL64).multiply(BigDecimal.valueOf(100));
    }

    /** Per-unit price |marketValue| / |quantity|; null when either is missing/zero (C1). */
    public static BigDecimal perUnitPrice(BigDecimal marketValue, BigDecimal quantity) {
        if (marketValue == null || quantity == null || quantity.signum() == 0) return null;
        return marketValue.abs().divide(quantity.abs(), MathContext.DECIMAL64);
    }
}
