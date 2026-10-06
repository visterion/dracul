package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.time.Instant;

/** One {@code savings_plan_month} row (V54). Amount and candidate count are null until the first
 *  pass that computes them. */
public record SavingsMonth(String month, BigDecimal monthAmountEur, Integer candidateCount,
        Instant completedAt, Instant missedAlertedAt) {
}
