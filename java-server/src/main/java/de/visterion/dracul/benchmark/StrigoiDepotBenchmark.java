package de.visterion.dracul.benchmark;

import java.math.BigDecimal;

/** Capital-weighted EUR return of one source Strigoi's executor positions vs EUNL over each
 *  position's own window (spec §4.2). returnEur/benchmarkReturn/excess are null when no position
 *  could be measured; skipped counts positions without price, FX or known currency. */
public record StrigoiDepotBenchmark(String strigoi, int positions, int openPositions, BigDecimal capitalEur,
                                    BigDecimal returnEur, BigDecimal benchmarkReturn, BigDecimal excess,
                                    int skipped) {
}
