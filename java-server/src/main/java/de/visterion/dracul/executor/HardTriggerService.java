package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.CloseResult;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.notify.TelegramNotifier;
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
 * <p>Precedence when multiple conditions are simultaneously breached: stop-breach, then the
 * structured kill level, then MFE-giveback — the first match names the reason. The kill level is
 * ONLY the hunter-authored number {@code kill_close_below} (BUY: close strictly below it); free-text
 * {@code kill_criteria} are never parsed here — a regex over them never matched one production
 * criterion (German wording, decimal comma) and a naive German one fires falsely on offer prices,
 * dated, multi-day and negated criteria (spec 2026-10-02 §1). They stay LLM context.
 *
 * <p>For exit profile CONVICTION (spec 2026-10-03 §5.4) the order is: a flagged catastrophe
 * (evaluated before the close-null skip), the stop, then the target-half (a 0.5 partial exit
 * through {@link PartialExitService}); kill level and giveback are STANDARD only.
 *
 * <p>On {@link BrokerUnavailableException} while flattening, this deliberately does nothing
 * to the book — a transient broker outage must never be mistaken for a closed position — and
 * escalates via the decision log instead. That includes a {@link BrokerRejectedException}: a
 * rejection is a broker VERDICT, not an outage, and {@code BROKER_UNAVAILABLE} must not carry
 * both meanings the way it did before the 2026-08-24 RGNX incident, where the position had long
 * been stopped out at the broker but the book still held it OPEN — the flatten call correctly
 * reached the broker and got a definite "no open position" verdict back, which is exactly the
 * state this service must be able to name, not a transport failure. See
 * {@link #flattenOrEscalate}.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class HardTriggerService {

    private static final Logger log = LoggerFactory.getLogger(HardTriggerService.class);

    /** Agora's reject code for the definite "there is no open position to flatten" verdict —
     *  the same typed field {@code AgoraExecutionGateway.requireAccepted} already threads through
     *  for {@code LEG_NOT_FOUND} (see {@code StopRatchetService}). Structural, not transient: no
     *  retry makes a gone position come back.
     *
     *  <p>Deliberately NOT {@code "NOT_FOUND"} (fix round 2): Agora's {@code FlattenTool} emits
     *  {@code NOT_FOUND} for a generic HTTP 404 reached elsewhere inside a flatten call (a
     *  related-orders lookup, the closing POST itself on a partial close) — that says nothing
     *  about whether the position exists, and folding it into "already gone" was the actual
     *  defect this round fixes. {@code NO_POSITION} is the narrower code Agora reserves for the
     *  one definite determination (a full scan of the broker's actual holdings coming back
     *  empty). A plain {@code NOT_FOUND} rejection still reaches {@link #flattenOrEscalate}'s
     *  {@code else} branch below and is named {@code BROKER_REJECTED} — a verdict, honestly not
     *  claimed to mean the position is gone.
     *
     *  <p>Named {@code AGORA_NO_POSITION}, not just {@code NO_POSITION}: {@code RejectReason}
     *  already declares a {@code NO_POSITION} value elsewhere in this package, meaning DRACUL's
     *  own book has no matching open position (the entry/add-tranche path) — a completely
     *  different check, on a completely different data source. One literal spelling, two
     *  meanings, must not share one name. */
    private static final String AGORA_NO_POSITION = "NO_POSITION";

    private final ExecutionGateway gateway;
    private final ExecutorPositionRepository positionRepo;
    private final DecisionLogRepository decisionRepo;
    private final CooldownRepository cooldownRepo;
    private final RuleVersionProvider ruleVersions;
    private final ObjectMapper mapper;
    private final double givebackPct;
    private final double givebackActiveFromR;
    private final int cooldownDays;
    private final PartialExitService partialExit;
    private final ConvictionProfile convictionProfile;
    private final TelegramNotifier telegram;
    private final Clock clock;

    @Autowired
    public HardTriggerService(
            ExecutionGateway gateway,
            ExecutorPositionRepository positionRepo,
            DecisionLogRepository decisionRepo,
            CooldownRepository cooldownRepo,
            RuleVersionProvider ruleVersions,
            ObjectMapper mapper,
            @Value("${dracul.executor.giveback-pct:0.35}") double givebackPct,
            @Value("${dracul.executor.giveback-active-from-r:1.5}") double givebackActiveFromR,
            @Value("${dracul.executor.cooldown-days:3}") int cooldownDays,
            PartialExitService partialExit,
            ConvictionProfile convictionProfile,
            TelegramNotifier telegram) {
        this(gateway, positionRepo, decisionRepo, cooldownRepo, ruleVersions, mapper,
                givebackPct, givebackActiveFromR, cooldownDays, partialExit, convictionProfile,
                telegram, Clock.systemUTC());
    }

    HardTriggerService(
            ExecutionGateway gateway,
            ExecutorPositionRepository positionRepo,
            DecisionLogRepository decisionRepo,
            CooldownRepository cooldownRepo,
            RuleVersionProvider ruleVersions,
            ObjectMapper mapper,
            double givebackPct,
            double givebackActiveFromR,
            int cooldownDays,
            PartialExitService partialExit,
            ConvictionProfile convictionProfile,
            TelegramNotifier telegram,
            Clock clock) {
        this.gateway = gateway;
        this.positionRepo = positionRepo;
        this.decisionRepo = decisionRepo;
        this.cooldownRepo = cooldownRepo;
        this.ruleVersions = ruleVersions;
        this.mapper = mapper;
        this.givebackPct = givebackPct;
        this.givebackActiveFromR = givebackActiveFromR;
        this.cooldownDays = cooldownDays;
        this.partialExit = partialExit;
        this.convictionProfile = convictionProfile;
        this.telegram = telegram;
        this.clock = clock;
    }

    public List<ExecutorPosition> apply(List<ExecutorPosition> openPositions,
            Map<String, BigDecimal> currentCloseBySymbol, String runId) {
        List<ExecutorPosition> survivors = new ArrayList<>();
        int levelsEvaluated = 0;
        int levelsBreached = 0;
        int catastrophesFlagged = 0;
        int targetsHit = 0;
        for (ExecutorPosition p : openPositions) {
            BigDecimal close = currentCloseBySymbol.get(p.symbol());

            // 0. CATASTROPHE (spec 2026-10-03 §5.4, R1 M2) — BEFORE the close-null skip: it needs
            // no price, and a halt or delisting (no close at all) is exactly its case. A pending
            // trim does not shield it: full flatten.
            if (p.catastropheReason() != null) {
                catastrophesFlagged++;
                Trigger catastrophe = new Trigger("HARD_CATASTROPHE", "CATASTROPHE",
                        "CATASTROPHE: " + p.catastropheReason());
                Instant detectedAt = clock.instant();
                CloseResult cr = flattenOrEscalate(p, catastrophe, runId);
                if (cr == null) {
                    survivors.add(p);
                    continue;
                }
                recordHardExit(p, close, close == null ? null : computeR(p, close), catastrophe,
                        runId, detectedAt, cr);
                continue;
            }

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
            boolean conviction = p.exitProfile() == ExitProfile.CONVICTION;
            BigDecimal currentR = computeR(p, close);

            if (!conviction && isBuy(p) && p.killCloseBelow() != null) {
                levelsEvaluated++;
                if (close.compareTo(p.killCloseBelow()) < 0) levelsBreached++;
            }

            // 1. stop breach — for CONVICTION the active stop is the emergency stop or the trail.
            Trigger trigger = detectStopBreach(p, close, sell);
            if (trigger == null && conviction) {
                // 2. TARGET_HALF — exactly once: never after the half-sale, never while a trim is
                // still pending (a second maintenance pass in the same run re-reads the trimmed
                // row and stops here). 3. kill level and giveback do not apply to this profile.
                if (targetHalfDue(p, close)) {
                    targetsHit++;
                    sellHalf(p, close, runId);
                }
                survivors.add(p);
                continue;
            }
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
        // One line per run, always — also when no position carries a level, so "the code-enforced
        // kill path saw nothing to enforce" is distinguishable from "the path did not run". Breached
        // counts every close below its level, including one where HARD_STOP took precedence.
        log.info("kill levels evaluated: {} of {} filled positions (breached: {}); "
                        + "catastrophe flagged: {}, targets hit: {}",
                levelsEvaluated, openPositions.size(), levelsBreached, catastrophesFlagged,
                targetsHit);
        return survivors;
    }

    /** Spec §5.4 #2: CONVICTION, no half-sale yet, no trim pending, close >= entry x (1 + target-pct). */
    private boolean targetHalfDue(ExecutorPosition p, BigDecimal close) {
        return isBuy(p) && p.trimCount() == 0 && p.pendingTrimOrderId() == null
                && close.compareTo(convictionProfile.targetPrice(p.entryPrice())) >= 0;
    }

    private void sellHalf(ExecutorPosition p, BigDecimal close, String runId) {
        BigDecimal fraction = convictionProfile.targetFraction();
        if (p.qty() == null || p.qty().multiply(fraction).setScale(0, RoundingMode.FLOOR).signum() == 0) {
            // Only reachable after a 1-share partial fill: min-entry-qty keeps entries >= 2.
            log.warn("target-half due for position {} ({}) but qty {} cannot be halved — skipped",
                    p.id(), p.symbol(), p.qty());
            return;
        }
        String reasoning = "TARGET_HALF: close " + plain(close) + " >= target "
                + plain(convictionProfile.targetPrice(p.entryPrice())) + " (entry "
                + plain(p.entryPrice()) + " x (1 + " + plain(convictionProfile.targetPct()) + "))";
        PartialExitService.Result result = partialExit.execute(p, fraction, "HARD_TRIGGER",
                "HARD_TARGET_HALF", "target-half flatten", reasoning, null, runId);
        if (result != null && result.outcome() == PartialExitService.Outcome.UNAVAILABLE) {
            markTargetHalfUnconfirmed(p, runId);
        }
    }

    /**
     * Final review #1: BROKER_UNAVAILABLE on the target-half is not "nothing happened" — the
     * gateway maps every transport failure to it, including a read timeout AFTER the POST reached
     * the broker. Leaving trim_count at 0 would let the next reconcile QTY_SYNC the book down to the
     * half the broker kept and the next close >= target sell half of THAT. So trim_count is bumped
     * (qty untouched — QTY_SYNC converges it to the broker), the escalation names the position and
     * an operator verifies at the broker. Only the TARGET_HALF path: the LLM's soft scale-out keeps
     * the plain BROKER_UNAVAILABLE semantics. {@code PartialExitService} has already written its
     * BROKER_UNAVAILABLE row; this one adds the unconfirmed-trim state on top.
     */
    private void markTargetHalfUnconfirmed(ExecutorPosition p, String runId) {
        int newTrimCount = p.trimCount() + 1;
        positionRepo.markTrimUnconfirmed(p.id(), newTrimCount);
        String text = "target-half partial close on " + p.symbol() + " (position " + p.id()
                + ") got no broker verdict — it may or may not have executed. trim_count set to "
                + newTrimCount + " so it is never sold twice; verify at broker; reset trim_count "
                + "to " + p.trimCount() + " if nothing sold";
        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("position_id", p.id());
        inputs.put("qty", p.qty());
        inputs.put("trim_count", newTrimCount);
        escalate(p, runId, "TARGET_HALF_UNCONFIRMED", text, inputs);
        telegram.notifyAlert(p.symbol(), "TARGET_HALF_UNCONFIRMED", "CRITICAL", text);
    }

    /**
     * Attempts to flatten the position; on any failure, escalates via the decision log and
     * returns null so the book is left untouched — the broker is never replaced by a guess.
     *
     * <p>{@code BROKER_UNAVAILABLE} is reserved for a call that got no verdict at all — not just
     * transport failure, 5xx or timeout, but any {@code available:false} tool result: an
     * unknown/inactive connection, the tool's own argument validation, a real outage. A
     * rejection ({@code accepted:false}, {@code available:true}) is a verdict, and is named
     * separately: {@code POSITION_ALREADY_GONE} for the structural case Agora reports as reject
     * code {@code AGORA_NO_POSITION} (the position no longer exists at the broker — no retry helps),
     * {@code BROKER_REJECTED} for every other reject code, including a generic {@code NOT_FOUND}
     * (still a verdict, but nothing here knows enough to say more than that). The wire code itself
     * is carried in {@code inputs_snapshot.reject_code} so it stays queryable.
     *
     * <p>{@code ExecutorWebhookController}'s soft-exit flatten files the same rejections under the
     * same two names and the same {@code reject_code} field. That is deliberate: they are the same
     * broker event, and while the two paths named it differently, a query for
     * {@code BROKER_REJECTED} found one of them and silently missed the other.
     */
    private CloseResult flattenOrEscalate(ExecutorPosition p, Trigger trigger, String runId) {
        try {
            return gateway.flatten(p.connection(), p.symbol(), BigDecimal.ONE);
        } catch (BrokerRejectedException e) {
            // The wire code also goes into inputs_snapshot, not only into the prose: one
            // reason_code covering every rejection is only queryable if the code that
            // distinguishes them is a field. Same shape ExecutorWebhookController writes for the
            // identical broker event -- the two paths speak one vocabulary.
            ObjectNode inputs = mapper.createObjectNode();
            inputs.put("reject_code", e.rejectCode());
            if (AGORA_NO_POSITION.equals(e.rejectCode())) {
                escalate(p, runId, "POSITION_ALREADY_GONE",
                        "position already gone during hard-trigger flatten: " + e.getMessage(),
                        inputs);
            } else {
                escalate(p, runId, "BROKER_REJECTED",
                        "broker rejected hard-trigger flatten ["
                                + (e.rejectCode() == null ? "no reject code" : e.rejectCode())
                                + "]: " + e.getMessage(),
                        inputs);
            }
            return null;
        } catch (BrokerUnavailableException e) {
            escalate(p, runId, "BROKER_UNAVAILABLE",
                    "broker unavailable during hard-trigger flatten: " + e.getMessage());
            return null;
        }
    }

    private void escalate(ExecutorPosition p, String runId, String reasonCode, String reasoning) {
        escalate(p, runId, reasonCode, reasoning, null);
    }

    private void escalate(ExecutorPosition p, String runId, String reasonCode, String reasoning,
            ObjectNode inputs) {
        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "HARD_TRIGGER", null, null, null, p.symbol(), inputs, null,
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
        // close/current_r are null for a CATASTROPHE without a price (halt/delisting) — never computed.
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

    // Reason codes produced here ("HARD_STOP", "HARD_KILL_CRITERIA", "GIVEBACK_BREACH",
    // "HARD_CATASTROPHE", "HARD_TARGET_HALF") are duplicated in ReconcileService#HARD_REASONS —
    // keep both in sync.
    //
    // CONVICTION after the half-sale (final review #2): the breach level is the tighter of
    // active_stop and the profile trail on highest_price (which already includes tonight's close).
    // StopRatchetService skips a trail candidate the close is already beyond (wrong-side guard), so
    // after a >= trail-pct drop between two ratchets active_stop still sits on the old level and
    // would never fire — the trail must be checked here, against the same close.
    private Trigger detectStopBreach(ExecutorPosition p, BigDecimal close, boolean sell) {
        BigDecimal trail = convictionTrail(p);
        boolean trailTighter = trail != null && (sell
                ? trail.compareTo(p.activeStop()) < 0
                : trail.compareTo(p.activeStop()) > 0);
        BigDecimal level = trailTighter ? trail : p.activeStop();
        boolean breached = sell
                ? close.compareTo(level) > 0
                : close.compareTo(level) < 0;
        if (!breached) return null;

        String measured = trailTighter
                ? "STOP_BREACH: close " + plain(close) + (sell ? " > trail " : " < trail ")
                        + plain(trail) + " (highest " + plain(p.highestPrice()) + " x (1 "
                        + (sell ? "+ " : "- ") + plain(convictionProfile.trailPct())
                        + "); active stop " + plain(p.activeStop()) + ")"
                : "STOP_BREACH: close " + plain(close) + (sell ? " > stop " : " < stop ")
                        + plain(p.activeStop());
        return new Trigger("HARD_STOP", "STOP_BREACH", measured);
    }

    /** The CONVICTION trail level once the half-sale happened, else null (STANDARD, no trim yet,
     *  or no recorded extreme). SELL would trail on the lowest close; the basket is long-only, so
     *  only the BUY side carries an extreme here. */
    private BigDecimal convictionTrail(ExecutorPosition p) {
        if (p.exitProfile() != ExitProfile.CONVICTION || p.trimCount() <= 0) return null;
        if (!isBuy(p) || p.highestPrice() == null) return null;
        return convictionProfile.trailStop(p.side(), p.highestPrice());
    }

    private Trigger detectKillCriteria(ExecutorPosition p, BigDecimal close) {
        String measured = killLevelBreach(p, close);
        return measured == null ? null : new Trigger("HARD_KILL_CRITERIA", "KILL_CRITERIA", measured);
    }

    /**
     * The structured kill-level breach of {@code p} at {@code close}, as the one human-readable
     * line both the hard trigger ({@code veto_results.measured}) and the soft-trigger context
     * ({@code soft_trigger.kill_criteria_breached}) carry — or null when there is none: a SELL, no
     * level, no close, or a close at/above the level. Strictly below: a close exactly AT the level
     * has not broken it.
     */
    static String killLevelBreach(ExecutorPosition p, BigDecimal close) {
        if (close == null || p.killCloseBelow() == null || !isBuy(p)) return null;
        if (close.compareTo(p.killCloseBelow()) >= 0) return null;
        return "KILL_LEVEL: close " + plain(close) + " < kill_close_below "
                + plain(p.killCloseBelow());
    }

    private static boolean isBuy(ExecutorPosition p) {
        return "BUY".equalsIgnoreCase(p.side());
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

    private static String plain(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }

    private String trimPct(double pct) {
        return pct == Math.floor(pct) ? String.valueOf((long) pct) : String.valueOf(pct);
    }

    /** One detected hard-exit condition, ready to be flattened and logged. */
    private record Trigger(String reasonCode, String check, String measured) {}
}
