package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared capital-bounds arithmetic (BUDGET + HEAT_LIMIT) used by both {@link VetoService}
 * (new-entry vetos 5/6) and {@code ExecutorWebhookController.addTranche} (tranche-2 adds) — one
 * implementation, two call sites, so the two paths can never silently drift apart.
 */
final class CapitalBounds {

    /** {@code trancheAccountCcy} is exposed so callers needing the tranche size (e.g. for sizing)
     *  don't have to recompute it. */
    record Result(boolean budgetOk, boolean heatOk, BigDecimal trancheAccountCcy) {}

    private CapitalBounds() {}

    static Result check(AccountSnapshot account, BigDecimal openExposure, BigDecimal openHeat,
            BigDecimal newRiskAccountCcy, BigDecimal totalBudget, int trancheCount, double heatPct) {
        BigDecimal trancheAccountCcy = totalBudget.divide(BigDecimal.valueOf(trancheCount), 2, RoundingMode.HALF_UP);
        return checkCharge(account, openExposure, openHeat, newRiskAccountCcy, totalBudget,
                trancheAccountCcy, heatPct);
    }

    /** Same arithmetic with an explicit charge: exit profile CONVICTION charges its actual
     *  profile notional instead of total-budget / tranche-count (spec 2026-10-03 §5.3, R1 M6).
     *  The charge is reported back as {@code trancheAccountCcy} so every consumer keeps one
     *  field to read. */
    static Result checkCharge(AccountSnapshot account, BigDecimal openExposure, BigDecimal openHeat,
            BigDecimal newRiskAccountCcy, BigDecimal totalBudget, BigDecimal chargeAccountCcy,
            double heatPct) {
        boolean budgetOk = account != null
                && account.cash().compareTo(chargeAccountCcy) >= 0
                && openExposure.add(chargeAccountCcy).compareTo(totalBudget) <= 0;

        BigDecimal heatLimit = totalBudget.multiply(BigDecimal.valueOf(heatPct));
        boolean heatOk = openHeat.add(newRiskAccountCcy).compareTo(heatLimit) <= 0;

        return new Result(budgetOk, heatOk, chargeAccountCcy);
    }
}
