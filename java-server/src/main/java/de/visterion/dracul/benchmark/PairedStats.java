package de.visterion.dracul.benchmark;

import java.math.BigDecimal;

/** Picked vs rejected candidate events. difference = clustered picked − clustered rejected; the
 *  interval uses a Newey–West variance per group (lag = horizon/5 weeks) and Welch–Satterthwaite df
 *  on (weeks − 1) per group — null unless both groups span max(5, 2·lag) weeks. */
public record PairedStats(int pickedN, BigDecimal pickedMean, int pickedWeeks, int rejectedN,
                          BigDecimal rejectedMean, int rejectedWeeks, BigDecimal difference,
                          BigDecimal ciLow, BigDecimal ciHigh) {
}
