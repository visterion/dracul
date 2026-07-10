package de.visterion.dracul.executor;

import java.math.BigDecimal;

/**
 * Outcome of PositionSizer: computed tranche quantity, per-share risk, total account-currency risk,
 * and the protective stop window validation.
 */
public record Sizing(
        BigDecimal qty,
        BigDecimal rPerShare,
        BigDecimal newRiskAccountCcy,
        BigDecimal stopMin,
        BigDecimal stopMax,
        boolean stopInWindow
) {}
