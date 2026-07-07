package de.visterion.dracul.gropar;

import java.math.BigDecimal;
import java.util.List;

/** Deterministic scale-out ladder: TRIM ~1/3 at +2R, ~1/3 at +4R, rest trails.
 *  Advice only — gropar never places orders. */
public final class ScaleOutLadder {

    /** Fractions of the position suggested at each rung (2R, 4R). */
    public static final List<BigDecimal> SCALE_OUT_FRACTIONS =
            List.of(new BigDecimal("0.3333"), new BigDecimal("0.3333"));

    private ScaleOutLadder() {}

    /** Price levels at +2R and +4R; empty when R is unknown. */
    public static List<BigDecimal> profitTargets(BigDecimal entry, BigDecimal r) {
        if (entry == null || r == null) return List.of();
        return List.of(
                entry.add(r.multiply(BigDecimal.valueOf(2))),
                entry.add(r.multiply(BigDecimal.valueOf(4))));
    }
}
