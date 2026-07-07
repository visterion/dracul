package de.visterion.dracul.voievod;

import java.util.Map;

/** Dracul-domain payoff taxonomy for consensus reasoning: DRIFT setups (open-ended upside,
 *  gradual repricing, ~3–12m) vs. EVENT setups (capped payoff / cliff downside, short,
 *  event-terminated). Corroboration across families is usually suspect. This is pure Dracul
 *  investment vocabulary — it must never leak into Agora. */
public enum PayoffFamily {
    DRIFT, EVENT, UNKNOWN;

    private static final Map<String, PayoffFamily> BY_ANOMALY = Map.of(
            "PEAD", DRIFT,
            "QUALITY_52W_LOW", DRIFT,
            "INSIDER_CLUSTER", DRIFT,
            "SPINOFF", DRIFT,
            "MERGER_ARB", EVENT,
            "INDEX_INCLUSION", EVENT);

    /** Maps a strigoi anomalyType string to its payoff family. null/blank/unknown -> UNKNOWN. */
    public static PayoffFamily of(String anomalyType) {
        if (anomalyType == null || anomalyType.isBlank()) return UNKNOWN;
        return BY_ANOMALY.getOrDefault(anomalyType, UNKNOWN);
    }
}
