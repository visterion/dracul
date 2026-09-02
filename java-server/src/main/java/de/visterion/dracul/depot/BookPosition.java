package de.visterion.dracul.depot;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One position as the backfill needs it: the book row plus the facts that only
 * {@code decision_log} carries.
 *
 * <p>{@code enterQty} is the quantity of the ENTER order — tranche 1. The remainder
 * ({@code qty - enterQty}) is tranche 2, which the book never dates. {@code qtySyncDate} is
 * the day a QTY_SYNC corrected the quantity, or null.
 *
 * <p>{@code fillDate} is the day of the FIRST {@code decision_log} SYNC row of ANY reason
 * code for this position (2026-09-02 design spec §2, §3.1) — the earliest broker
 * corroboration that the position was actually held, as opposed to {@code entryDate}, which
 * is only when the book says it was entered. Null means no SYNC row exists at all for this
 * position, i.e. no corroboration.
 *
 * <p>Both tranches are priced at {@code entryPrice}, the weighted average. The per-tranche
 * fill prices do not exist in any source (spec §1.1). Using the average keeps the total cash
 * movement exact — {@code t1*ep + t2*ep == qty*ep} — and only the split across two adjacent
 * days is approximate; spec §1.5 bounds that error at about 1.2 % of the account.
 */
public record BookPosition(long id, String symbol, String status, BigDecimal qty,
                           BigDecimal entryPrice, LocalDate entryDate,
                           BigDecimal exitPrice, LocalDate closedAt,
                           BigDecimal enterQty, LocalDate qtySyncDate, LocalDate fillDate) {
}
