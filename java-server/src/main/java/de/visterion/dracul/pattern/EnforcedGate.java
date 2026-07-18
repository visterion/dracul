package de.visterion.dracul.pattern;

/**
 * An enforceable gate loaded for the veto path (spec T3.3 D4): an ACTIVE pattern with a
 * non-null gate. {@code gateJson} is the raw stored JSON — parsing happens at the
 * evaluation site (defense in depth: a malformed stored gate is skipped with WARN there,
 * never crashing the veto catalog).
 */
public record EnforcedGate(String id, String name, String gateJson) {}
