package de.visterion.dracul.executor.pnl;

import java.math.BigDecimal;
import java.util.List;

/**
 * One executor position as a trade of the strigoi P&L view (spec 2026-10-06). Prices are in
 * {@code currency} (the executor's instrument currency); the two money fields are EUR at the
 * CURRENT rate. {@code realizedEur} covers every TRIM leg plus, for CLOSED rows, the final leg;
 * {@code unrealizedEur} is non-null only for an OPEN row with a known current price.
 *
 * <p>A null amount is never a zero: {@code flags} says why
 * ({@link StrigoiPnlCalculator#FLAG_INCOMPLETE_LEGS}, {@link StrigoiPnlCalculator#FLAG_NO_PRICE},
 * {@link StrigoiPnlCalculator#FLAG_NO_FX}). {@code r} is {@code executor_position.realized_r}
 * (final-leg R), null while OPEN.
 */
public record PnlTrade(Long positionId, String symbol, String status, String entryDate,
        String exitDate, BigDecimal qty, BigDecimal entryPrice, BigDecimal exitPrice, String currency,
        BigDecimal realizedEur, BigDecimal unrealizedEur, BigDecimal r, String exitReason,
        List<String> flags) {
}
