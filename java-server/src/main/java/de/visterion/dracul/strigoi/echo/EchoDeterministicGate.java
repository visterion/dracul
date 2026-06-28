package de.visterion.dracul.strigoi.echo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Centralised deterministic hard-skip gate for Echo PEAD candidates. Drops a candidate when its
 * earnings beat is accrual-driven (low quality), when a confounding corporate event makes the
 * announcement-CAR not the drift signal, or when the next earnings report is too close (event risk
 * overlays the drift). Reason precedence: confounder → accrual → timing. Conservative: a missing
 * (unavailable) accrual ratio or unknown next-earnings date never triggers a skip.
 */
@Component
public class EchoDeterministicGate {

    private final BigDecimal maxAccrualRatio;
    private final int minDaysToNextEarnings;

    public EchoDeterministicGate(
            @Value("${dracul.strigoi.echo.gate.max-accrual-ratio:0.10}") BigDecimal maxAccrualRatio,
            @Value("${dracul.strigoi.echo.gate.min-days-to-next-earnings:10}") int minDaysToNextEarnings) {
        this.maxAccrualRatio = maxAccrualRatio;
        this.minDaysToNextEarnings = minDaysToNextEarnings;
    }

    public GateDecision evaluate(AccrualMetrics accruals, List<String> confounderFlags, Integer daysToNextEarnings) {
        if (confounderFlags != null && !confounderFlags.isEmpty()) {
            return GateDecision.skip("confounder: " + String.join(",", confounderFlags));
        }
        if (accruals != null && accruals.available() && accruals.accrualRatio() != null
                && accruals.accrualRatio().compareTo(maxAccrualRatio) > 0) {
            return GateDecision.skip("accrual-driven (ratio " + accruals.accrualRatio() + " > " + maxAccrualRatio + ")");
        }
        if (daysToNextEarnings != null && daysToNextEarnings < minDaysToNextEarnings) {
            return GateDecision.skip("next earnings in " + daysToNextEarnings + "d (< " + minDaysToNextEarnings + ")");
        }
        return GateDecision.keep();
    }
}
