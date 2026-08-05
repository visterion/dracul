package de.visterion.dracul.gropar;

import java.math.BigDecimal;

/** Neutral bundle of technical indicators from Agora get_indicators. *Available=false when unavailable. */
public record ExitTa(
        BigDecimal atr, boolean atrAvailable,
        BigDecimal chandelierStop, boolean chandelierBreached,
        BigDecimal maFast, boolean maFastAvailable,
        BigDecimal maSlow, boolean maSlowAvailable,
        String maCrossState,
        BigDecimal high52w, BigDecimal low52w, boolean window52wAvailable) {

    /** All-unavailable fallback (Agora unreachable). A symbol too young for any indicator window
     *  is NOT an outage — Agora answers with per-value available:false and the mapper below
     *  produces this same all-false shape from that body. */
    public static ExitTa unavailable() {
        return new ExitTa(null, false, null, false, null, false, null, false,
                "NEUTRAL", null, null, false);
    }
}
