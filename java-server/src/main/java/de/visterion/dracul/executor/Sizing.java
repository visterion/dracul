package de.visterion.dracul.executor;

import java.math.BigDecimal;

/**
 * Outcome of PositionSizer: computed tranche quantity, per-share risk, total account-currency
 * risk, and the protective stop window validation.
 *
 * <p>Since SP1 the quantity is the MINIMUM of two independent caps — what a fixed notional tranche
 * buys, and what the account-currency risk budget allows at this stop distance. Both are reported
 * so the audit trail can say which one bound; {@code sizingBasis} names it.
 */
public record Sizing(
        BigDecimal qty,
        BigDecimal rPerShare,
        BigDecimal newRiskAccountCcy,
        BigDecimal stopMin,
        BigDecimal stopMax,
        boolean stopInWindow,
        /** Human-readable audit trail of which anchor won: the ATR-only baseline, or a wider
         *  swing-low. Names the ATR window actually used (e.g. {@code ATR22} or {@code ATR5}),
         *  never a hard-coded one. Null only when qty is zero and no anchor was ever chosen. */
        String stopBasis,
        /** Shares a fixed notional tranche buys at this price, floored. */
        BigDecimal qtyNotional,
        /** Shares the account-currency risk budget allows at this stop distance, floored. */
        BigDecimal qtyRisk,
        /** {@code "NOTIONAL"} or {@code "RISK"} — which cap produced {@code qty}. Null on every
         *  zero path. Ties go to {@code "NOTIONAL"}: the notional cap is the pre-existing rule. */
        String sizingBasis,
        /** Non-null exactly when {@code qty} is zero. The controller routes each cause to its own
         *  reject reason, so folding two of them together changes a decision's audit trail. */
        RejectCause rejectCause) {

    /** Why a sizing produced zero shares. */
    public enum RejectCause {
        /** A fixed tranche does not buy one whole share at this price. */
        NOTIONAL_ZERO,
        /** Risk-per-share is zero or negative (stop at or through the entry) — nothing to divide by. */
        NO_R,
        /** The stop distance in account currency exceeds the entire per-trade risk budget. */
        RISK_ZERO
    }
}
