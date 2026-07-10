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
        boolean budgetOk = account != null
                && account.cash().compareTo(trancheAccountCcy) >= 0
                && openExposure.add(trancheAccountCcy).compareTo(totalBudget) <= 0;

        BigDecimal heatLimit = totalBudget.multiply(BigDecimal.valueOf(heatPct));
        boolean heatOk = openHeat.add(newRiskAccountCcy).compareTo(heatLimit) <= 0;

        return new Result(budgetOk, heatOk, trancheAccountCcy);
    }
}
