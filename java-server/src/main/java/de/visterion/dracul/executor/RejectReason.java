package de.visterion.dracul.executor;

/** Code-enforced rejection reasons. Slice 1 wires SCHEMA_INVALID, LOW_CONFIDENCE, MAX_POSITIONS. */
public enum RejectReason {
    SCHEMA_INVALID,
    LOW_CONFIDENCE,
    MAX_POSITIONS,
    NO_STOP,
    MAX_TRANCHE,
    NON_SIM_CONNECTION
}
