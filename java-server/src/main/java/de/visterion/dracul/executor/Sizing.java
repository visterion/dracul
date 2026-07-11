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
        boolean stopInWindow,
        /** Human-readable audit trail of which anchor won: the ATR-only baseline, or a wider
         *  swing-low. Null only when qty is zero and no anchor was ever chosen. */
        String stopBasis
) {}
