package de.visterion.dracul.executor;

import java.math.BigDecimal;

/** Sizer-computed protective-stop window; stopMin <= stopMax on both sides. */
public record StopWindow(BigDecimal stopMin, BigDecimal stopMax, String stopBasis) {}
