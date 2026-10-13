package de.visterion.dracul.benchmark;

import java.math.BigDecimal;

/** Picked vs rejected candidate events. difference = clustered picked − clustered rejected, with a
 *  Welch interval over weekly means (null unless both groups span ≥ 5 weeks). */
public record PairedStats(int pickedN, BigDecimal pickedMean, int pickedWeeks, int rejectedN,
                          BigDecimal rejectedMean, int rejectedWeeks, BigDecimal difference,
                          BigDecimal ciLow, BigDecimal ciHigh) {
}
