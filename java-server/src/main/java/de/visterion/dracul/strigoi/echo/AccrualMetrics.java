package de.visterion.dracul.strigoi.echo;

import java.math.BigDecimal;

/** Sloan (1996) earnings-quality accrual ratio. {@code available} false = could not compute. */
public record AccrualMetrics(BigDecimal accrualRatio, boolean available) {
    public static AccrualMetrics unavailable() {
        return new AccrualMetrics(null, false);
    }
}
