package de.visterion.dracul.strigoi.echo;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One fiscal quarter of reported diluted EPS. */
public record QuarterlyEps(LocalDate periodEnd, BigDecimal eps) {}
