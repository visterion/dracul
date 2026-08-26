package de.visterion.dracul.executor;

import de.visterion.dracul.criteria.KillCriteriaEvaluator;
import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.CloseResult;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * escalates via the decision log instead. That includes a {@link BrokerRejectedException}: a
 * rejection is a broker VERDICT, not an outage, and {@code BROKER_UNAVAILABLE} must not carry
 * both meanings the way it did before the 2026-08-24 RGNX incident, where the position had long
 * been stopped out at the broker but the book still held it OPEN — the flatten call correctly
 * reached the broker and got "no open position" back, which is exactly the state this service
 * must be able to name, not a transport failure. See {@link #flattenOrEscalate}.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class HardTriggerService {

    private static final Logger log = LoggerFactory.getLogger(HardTriggerService.class);

    /** Agora's reject code for "there is no open position to flatten" — the same typed field
     *  {@code AgoraExecutionGateway.requireAccepted} already threads through for {@code LEG_NOT_FOUND}
     *  (see {@code StopRatchetService}). Structural, not transient: no retry makes a gone position
     *  come back. */
    private static final String NO_POSITION = "NOT_FOUND";

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
                // Deliberately UNCHANGED behaviour: the position survives. But it survives
                // WITHOUT its stop breach and kill criteria having been evaluated, on the
                // code-enforced hard-exit path the LLM may never override — and until this
                // line, that was indistinguishable from a position that was evaluated and
                // simply did not trigger. Usually caused upstream by MaintenancePipeline
                // dropping the symbol when its indicators were unavailable.
                log.warn("hard trigger skipped: position={} symbol={} — no close price, "
                        + "stop breach and kill criteria NOT evaluated this run",
                        p.id(), p.symbol());
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

            // Latency anchor BEFORE the flatten call: trigger_to_order_seconds must span
            // detection -> order placement, so capturing it after the flatten (inside
            // recordHardExit) would measure ~0 always and make the metric useless.
            Instant detectedAt = clock.instant();

            CloseResult cr = flattenOrEscalate(p, trigger, runId);
            if (cr == null) {
                survivors.add(p);
                continue;
            }

            recordHardExit(p, close, currentR, trigger, runId, detectedAt, cr);
        }
        return survivors;
    }

    /**
     * Attempts to flatten the position; on any failure, escalates via the decision log and
     * returns null so the book is left untouched — the broker is never replaced by a guess.
     *
     * <p>{@code BROKER_UNAVAILABLE} is reserved for a call that got no verdict at all: transport
     * failure, 5xx, timeout. A rejection is a verdict, and is named separately:
     * {@code POSITION_ALREADY_GONE} for the structural case Agora reports as reject code
     * {@code NOT_FOUND} (the position no longer exists at the broker — no retry helps),
     * {@code BROKER_REJECTED} for every other reject code (still a verdict, but nothing here
     * knows enough to say more).
     */
    private CloseResult flattenOrEscalate(ExecutorPosition p, Trigger trigger, String runId) {
        try {
            return gateway.flatten(p.connection(), p.symbol(), BigDecimal.ONE);
        } catch (BrokerRejectedException e) {
            if (NO_POSITION.equals(e.rejectCode())) {
                escalate(p, runId, "POSITION_ALREADY_GONE",
                        "position already gone during hard-trigger flatten: " + e.getMessage());
            } else {
                escalate(p, runId, "BROKER_REJECTED",
                        "broker rejected hard-trigger flatten ["
                                + (e.rejectCode() == null ? "no reject code" : e.rejectCode())
                                + "]: " + e.getMessage());
            }
            return null;
        } catch (BrokerUnavailableException e) {
            escalate(p, runId, "BROKER_UNAVAILABLE",
                    "broker unavailable during hard-trigger flatten: " + e.getMessage());
            return null;
        }
    }

    private void escalate(ExecutorPosition p, String runId, String reasonCode, String reasoning) {
        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "HARD_TRIGGER", null, null, null, p.symbol(), null, null,
                "ESCALATE", reasonCode, null, reasoning, null, null, null));
    }

    /**
     * The flatten was accepted by the broker, but not yet confirmed filled — stamp a
     * pending-exit marker instead of closing the book. {@link ReconcileService} finalizes
     * (books the CLOSED row + cooldown) once the broker no longer holds the position and the
     * exit order is no longer working. Closing here on the stale {@code close} price is exactly
     * the PSMT incident: the broker can still hold shares + a working exit order after a
     * flatten is merely accepted, not filled.
     */
    private void recordHardExit(ExecutorPosition p, BigDecimal close, BigDecimal currentR,
            Trigger trigger, String runId, Instant detectedAt, CloseResult cr) {
        positionRepo.markPendingExit(p.id(), trigger.reasonCode(), cr.orderRef(),
                cr.avgFillPrice(), clock.instant());

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
        latency.put("trigger_to_order_seconds", Duration.between(detectedAt, clock.instant()).getSeconds());

        // Exact position linkage for the outcome batch job (decision_log has no position_id
        // column; order_json carries it). A hard exit is always a full flatten -> fraction 1.0,
        // mirroring the EXIT_FULL row's shape.
        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("fraction", 1.0);
        orderJson.put("position_id", p.id());

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "HARD_TRIGGER", null, null, null, p.symbol(), inputs, vetoResults,
                "LOG_HARD_EXIT", trigger.reasonCode(), orderJson, null, null, latency, null));
    }

    // Reason codes produced below ("HARD_STOP", "HARD_KILL_CRITERIA", "GIVEBACK_BREACH") are
    // duplicated in ReconcileService#HARD_REASONS — keep both in sync.
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
