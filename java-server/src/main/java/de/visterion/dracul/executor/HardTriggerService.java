package de.visterion.dracul.executor;

import de.visterion.dracul.criteria.KillCriteriaEvaluator;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic hard exits: stop-breach and MFE-giveback. Code-enforced, never overridden by
 * an LLM's judgment — mirrors {@link ReconcileService}'s idiom for gateway/repo wiring,
 * decision-log construction, and cooldown bookkeeping.
 *
 * <p>Precedence when multiple conditions are simultaneously breached: stop-breach, then
 * measurable kill-criteria, then MFE-giveback — the first match names the reason. Kill-criteria
 * covers only measurable, price-level free-text criteria (via {@link KillCriteriaEvaluator});
 * qualitative criteria are left to the LLM elsewhere and never trigger here.
 *
 * <p>On {@link BrokerUnavailableException} while flattening, this deliberately does nothing
 * to the book — a transient broker outage must never be mistaken for a closed position — and
 * escalates via the decision log instead.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class HardTriggerService {

    private final ExecutionGateway gateway;
    private final ExecutorPositionRepository positionRepo;
    private final DecisionLogRepository decisionRepo;
    private final CooldownRepository cooldownRepo;
    private final RuleVersionProvider ruleVersions;
    private final ObjectMapper mapper;
    private final KillCriteriaEvaluator killCriteriaEvaluator;
    private final double givebackPct;
    private final double givebackActiveFromR;
    private final int cooldownDays;
    private final Clock clock;

    @Autowired
    public HardTriggerService(
            ExecutionGateway gateway,
            ExecutorPositionRepository positionRepo,
            DecisionLogRepository decisionRepo,
            CooldownRepository cooldownRepo,
            RuleVersionProvider ruleVersions,
            ObjectMapper mapper,
            KillCriteriaEvaluator killCriteriaEvaluator,
            @Value("${dracul.executor.giveback-pct:0.35}") double givebackPct,
            @Value("${dracul.executor.giveback-active-from-r:1.5}") double givebackActiveFromR,
            @Value("${dracul.executor.cooldown-days:10}") int cooldownDays) {
        this(gateway, positionRepo, decisionRepo, cooldownRepo, ruleVersions, mapper,
                killCriteriaEvaluator, givebackPct, givebackActiveFromR, cooldownDays, Clock.systemUTC());
    }

    HardTriggerService(
            ExecutionGateway gateway,
            ExecutorPositionRepository positionRepo,
            DecisionLogRepository decisionRepo,
            CooldownRepository cooldownRepo,
            RuleVersionProvider ruleVersions,
            ObjectMapper mapper,
            KillCriteriaEvaluator killCriteriaEvaluator,
            double givebackPct,
            double givebackActiveFromR,
            int cooldownDays,
            Clock clock) {
        this.gateway = gateway;
        this.positionRepo = positionRepo;
        this.decisionRepo = decisionRepo;
        this.cooldownRepo = cooldownRepo;
        this.ruleVersions = ruleVersions;
        this.mapper = mapper;
        this.killCriteriaEvaluator = killCriteriaEvaluator;
        this.givebackPct = givebackPct;
        this.givebackActiveFromR = givebackActiveFromR;
        this.cooldownDays = cooldownDays;
        this.clock = clock;
    }

    public List<ExecutorPosition> apply(List<ExecutorPosition> openPositions,
            Map<String, BigDecimal> currentCloseBySymbol, String runId) {
        List<ExecutorPosition> survivors = new ArrayList<>();
        for (ExecutorPosition p : openPositions) {
            BigDecimal close = currentCloseBySymbol.get(p.symbol());
            if (close == null) {
                survivors.add(p);
                continue;
            }

            boolean sell = "SELL".equals(p.side());
            BigDecimal currentR = computeR(p, close);

            Trigger trigger = detectStopBreach(p, close, sell);
            if (trigger == null) {
                trigger = detectKillCriteria(p, close);
            }
            if (trigger == null) {
                trigger = detectGiveback(p, currentR);
            }

            if (trigger == null) {
                survivors.add(p);
                continue;
            }

            if (!flattenOrEscalate(p, trigger, runId)) {
                survivors.add(p);
                continue;
            }

            recordHardExit(p, close, currentR, trigger, runId);
        }
        return survivors;
    }

    /** Attempts to flatten the position; on broker outage, escalates and returns false. */
    private boolean flattenOrEscalate(ExecutorPosition p, Trigger trigger, String runId) {
        try {
            gateway.flatten(p.connection(), p.symbol(), BigDecimal.ONE);
            return true;
        } catch (BrokerUnavailableException e) {
            decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                    "HARD_TRIGGER", null, null, null, p.symbol(), null, null,
                    "ESCALATE", "BROKER_UNAVAILABLE", null,
                    "broker unavailable during hard-trigger flatten: " + e.getMessage(),
                    null, null, null));
            return false;
        }
    }

    private void recordHardExit(ExecutorPosition p, BigDecimal close, BigDecimal currentR,
            Trigger trigger, String runId) {
        Instant tStart = clock.instant();

        positionRepo.close(p.id(), close, currentR, trigger.reasonCode());
        cooldownRepo.add(p.symbol(), trigger.reasonCode(),
                clock.instant().plus(Duration.ofDays(cooldownDays)), "fresh setup only");

        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("close", close);
        inputs.put("active_stop", p.activeStop());
        inputs.put("mfe_r", p.mfeR());
        inputs.put("current_r", currentR);

        ArrayNode vetoResults = mapper.createArrayNode();
        ObjectNode veto = mapper.createObjectNode();
        veto.put("check", trigger.check());
        veto.put("passed", false);
        veto.put("measured", trigger.measured());
        vetoResults.add(veto);

        ObjectNode latency = mapper.createObjectNode();
        latency.put("trigger_to_order_seconds", Duration.between(tStart, clock.instant()).getSeconds());

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "HARD_TRIGGER", null, null, null, p.symbol(), inputs, vetoResults,
                "LOG_HARD_EXIT", trigger.reasonCode(), null, null, null, latency, null));
    }

    private Trigger detectStopBreach(ExecutorPosition p, BigDecimal close, boolean sell) {
        boolean breached = sell
                ? close.compareTo(p.activeStop()) > 0
                : close.compareTo(p.activeStop()) < 0;
        if (!breached) return null;

        String measured = "STOP_BREACH: close " + plain(close) + (sell ? " > stop " : " < stop ")
                + plain(p.activeStop());
        return new Trigger("HARD_STOP", "STOP_BREACH", measured);
    }

    private Trigger detectKillCriteria(ExecutorPosition p, BigDecimal close) {
        List<String> breached = killCriteriaEvaluator.breached(p.killCriteria(), close);
        if (breached.isEmpty()) return null;
        String measured = "KILL_CRITERIA: close " + plain(close) + " breaches: \""
                + String.join("\"; \"", breached) + "\"";
        return new Trigger("HARD_KILL_CRITERIA", "KILL_CRITERIA", measured);
    }

    private Trigger detectGiveback(ExecutorPosition p, BigDecimal currentR) {
        if (p.mfeR() == null || currentR == null) return null;
        if (p.mfeR().doubleValue() < givebackActiveFromR) return null;

        BigDecimal threshold = p.mfeR().multiply(BigDecimal.valueOf(1 - givebackPct));
        if (currentR.compareTo(threshold) > 0) return null;

        double retainedPct = (1 - givebackPct) * 100;
        String measured = "GIVEBACK: current " + plain(currentR) + "R <= " + plain(threshold)
                + "R (" + trimPct(retainedPct) + "% of " + plain(p.mfeR()) + "R peak)";
        return new Trigger("GIVEBACK_BREACH", "GIVEBACK", measured);
    }

    private BigDecimal computeR(ExecutorPosition p, BigDecimal close) {
        BigDecimal numerator;
        BigDecimal denominator;
        if ("SELL".equals(p.side())) {
            numerator = p.entryPrice().subtract(close);
            denominator = p.initialStop().subtract(p.entryPrice());
        } else {
            numerator = close.subtract(p.entryPrice());
            denominator = p.entryPrice().subtract(p.initialStop());
        }
        if (denominator.compareTo(BigDecimal.ZERO) == 0) return null;
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private String plain(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }

    private String trimPct(double pct) {
        return pct == Math.floor(pct) ? String.valueOf((long) pct) : String.valueOf(pct);
    }

    /** One detected hard-exit condition, ready to be flattened and logged. */
    private record Trigger(String reasonCode, String check, String measured) {}
}
