package de.visterion.dracul.pattern;

import java.math.BigDecimal;

/**
 * The caller-built, executor-independent snapshot of the five gate-checkable signal fields
 * (spec T3.3 D1). Every component is nullable: a null field makes conditions over it
 * unevaluable, and the gate fails open.
 */
public record GateSignalView(String mechanism, String symbol, String sector, Double confidence,
        BigDecimal price) {}
