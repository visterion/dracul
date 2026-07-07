package de.visterion.dracul.strigoi.lazarus;

import java.math.BigDecimal;

/** Dracul's read of Agora's piotroskiF score. {@code available}=false when Agora
 *  returned no usable score. {@code cfoExceedsNetIncomeAvailable} gates the accruals drop. */
public record FundamentalScore(
        int score,
        int criteriaAvailable,
        BigDecimal accrualRatio,          // may be null
        boolean cfoExceedsNetIncome,
        boolean cfoExceedsNetIncomeAvailable,
        boolean available) {
    public static FundamentalScore unavailable() {
        return new FundamentalScore(0, 0, null, false, false, false);
    }
}
