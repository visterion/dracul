package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.CloseResult;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.executor.broker.RestoredLeg;
import de.visterion.dracul.notify.TelegramNotifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The one implementation of a PARTIAL exit (spec 2026-10-03 §5.7), shared by the LLM's soft
 * scale-out ({@code exit_position} with a fraction below 1) and exit profile CONVICTION's
 * TARGET_HALF hard trigger.
 *
 * <p><b>Why it exists — a latent bug in the old trim path.</b> {@code recordTrim} repoints only the
 * position COLUMNS {@code stop_order_id}/{@code tranche2_stop_order_id}; the
 * {@code executor_position_leg} rows kept the cancelled stop ids, and StopRatchetService,
 * {@code syncLegQuantities}, {@code matchLegFills} and {@code legExitReason} address legs by
 * {@code leg.stopOrderId()} — so after a partial close the ratchet escalated STOP_LEG_MISSING
 * forever and the remainder's stop fill was booked RECONCILE_GONE (R1 C1). Here an accepted trim
 * repoints the leg rows to {@code protective_legs[].order_id} and CLOSES a leg no restored leg
 * replaces (its shares left with the trim — nulling it would leave a dead OPEN leg, R2 Minor 3).
 * The close is only safe when at least one leg WAS matched: a restored list that names no leg at
 * all (stale/unknown ids) would otherwise close every leg, and the next reconcile's
 * {@code completeInterruptedClose} would book the whole position CLOSED while the broker still
 * holds the remainder. That case leaves the rows alone and escalates {@code TRIM_LEGS_UNMATCHED}.
 *
 * <p><b>Fill price.</b> The broker's partial close may answer without a fill price (Saxo never
 * returns one); the order id is then stored as {@code pending_trim_order_id} and the TRIM row
 * carries it, so {@code ReconcileService} can write the {@code TRIM_FILL} row from the fill
 * history later.
 *
 * <p><b>Rejections</b> keep the pre-existing reject-branch semantics verbatim
 * ({@link #escalateRejected}): repoint/null the stop ids Agora's rollback touched, never touch
 * qty/trim_count, page CRITICAL on LEG_RESTORE_FAILED_UNPROTECTED. The full-exit branch of
 * {@code exit_position} reuses that method, so a rejection is named identically on every path.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class PartialExitService {

    /** Agora's reject code for the definite "there is no open position to flatten" verdict —
     *  the same typed field {@code AgoraExecutionGateway.requireAccepted} threads through for
     *  {@code LEG_NOT_FOUND} (see {@code StopRatchetService}). Structural, not transient: no
     *  retry brings a gone position back, and it must not be filed as
     *  {@code BROKER_UNAVAILABLE} — that is exactly the 2026-08-24 RGNX incident, where the
     *  broker had long stopped the position out but the book still held it OPEN.
     *
     *  <p>Deliberately NOT {@code "NOT_FOUND"} (fix round 2): Agora's {@code FlattenTool} emits
     *  the generic {@code NOT_FOUND} for an HTTP 404 reached elsewhere inside a flatten call (a
     *  related-orders lookup, the closing POST itself on a partial close) — that says nothing
     *  about whether the position exists. {@code NO_POSITION} is the narrower code Agora reserves
     *  for the one definite determination. A plain {@code NOT_FOUND} rejection still falls
     *  through to the generic branch below, named by its own raw reject code — a verdict,
     *  honestly not claimed to mean the position is gone.
     *  (Moved here from ExecutorWebhookController with the reject branch.) */
    private static final String AGORA_NO_POSITION = "NO_POSITION";

    public enum Outcome { TRIMMED, REJECTED, UNAVAILABLE }

    /** {@code qtyClosed}/{@code qtyRemaining} are the broker's numbers when it reported both,
     *  otherwise the local floor arithmetic; null unless TRIMMED. */
    public record Result(Outcome outcome, BigDecimal qtyClosed, BigDecimal qtyRemaining,
            BigDecimal fillPrice, String orderId) {
        public boolean trimmed() {
            return outcome == Outcome.TRIMMED;
        }
    }

    private final ExecutionGateway gateway;
    private final ExecutorPositionRepository positionRepo;
    private final ExecutorPositionLegRepository legRepo;
    private final DecisionLogRepository decisionLogRepo;
    private final RuleVersionProvider ruleVersions;
    private final ObjectMapper mapper;
    private final TelegramNotifier telegram;
    private final Clock clock;

    @Autowired
    public PartialExitService(ExecutionGateway gateway, ExecutorPositionRepository positionRepo,
            ExecutorPositionLegRepository legRepo, DecisionLogRepository decisionLogRepo,
            RuleVersionProvider ruleVersions, ObjectMapper mapper, TelegramNotifier telegram) {
        this(gateway, positionRepo, legRepo, decisionLogRepo, ruleVersions, mapper, telegram,
                Clock.systemUTC());
    }

    PartialExitService(ExecutionGateway gateway, ExecutorPositionRepository positionRepo,
            ExecutorPositionLegRepository legRepo, DecisionLogRepository decisionLogRepo,
            RuleVersionProvider ruleVersions, ObjectMapper mapper, TelegramNotifier telegram,
            Clock clock) {
        this.gateway = gateway;
        this.positionRepo = positionRepo;
        this.legRepo = legRepo;
        this.decisionLogRepo = decisionLogRepo;
        this.ruleVersions = ruleVersions;
        this.mapper = mapper;
        this.telegram = telegram;
        this.clock = clock;
    }

    /**
     * Sells {@code fraction} (strictly between 0 and 1 — the caller has already decided this is
     * NOT a full exit) of a filled position.
     *
     * @param triggerType {@code SOFT_TRIGGER} (LLM) or {@code HARD_TRIGGER} (TARGET_HALF)
     * @param reasonCode reason_code of the TRIM row (null on the LLM path, as before)
     * @param what the noun phrase used in escalation texts ("soft-exit flatten", "target-half flatten")
     */
    public Result execute(ExecutorPosition p, BigDecimal fraction, String triggerType,
            String reasonCode, String what, String reasoning, Double confidence, String runId) {
        CloseResult cr;
        try {
            cr = gateway.flatten(p.connection(), p.symbol(), fraction);
        } catch (BrokerRejectedException e) {
            escalateRejected(p, e, triggerType, what, confidence, runId);
            return new Result(Outcome.REJECTED, null, null, null, null);
        } catch (BrokerUnavailableException e) {
            escalateUnavailable(p, e, triggerType, what, confidence, runId);
            return new Result(Outcome.UNAVAILABLE, null, null, null, null);
        }

        // Quantities come from the BROKER, never from our own arithmetic (the broker floors
        // qty x fraction and subtracts; Dracul would floor qty x (1 - fraction)). The local
        // fallback is all-or-nothing, see the old exit_position comment.
        BigDecimal localRemaining = p.qty().multiply(BigDecimal.ONE.subtract(fraction))
                .setScale(0, RoundingMode.FLOOR);
        boolean brokerReportedBoth = cr.closedQty() != null && cr.remainingQty() != null;
        BigDecimal qtyClosed = brokerReportedBoth ? cr.closedQty() : p.qty().subtract(localRemaining);
        BigDecimal qtyRemaining = brokerReportedBoth ? cr.remainingQty() : localRemaining;
        List<RestoredLeg> restored = cr.protectiveLegs() == null ? List.of() : cr.protectiveLegs();

        positionRepo.recordTrim(p.id(), qtyRemaining, p.trimCount() + 1, restored, cr.legsCollapsed());
        List<String> unmatchedLegStops = repointLegsAfterAcceptedTrim(p.id(), restored);
        if (cr.avgFillPrice() == null && cr.orderRef() != null) {
            positionRepo.markPendingTrim(p.id(), cr.orderRef());
        }

        ObjectNode orderJson = mapper.createObjectNode();
        orderJson.put("fraction", fraction.doubleValue());
        orderJson.put("qty_closed", qtyClosed);
        orderJson.put("qty_remaining", qtyRemaining);
        orderJson.put("price", cr.avgFillPrice());
        // Exact position linkage for the outcome batch job (decision_log has no position_id
        // column) and the order id ReconcileService resolves the fill price by.
        orderJson.put("position_id", p.id());
        orderJson.put("order_id", cr.orderRef());
        decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(), triggerType,
                null, null, null, p.symbol(), null, null, "TRIM", reasonCode, orderJson,
                reasoning, confidence, null, null));
        if (!unmatchedLegStops.isEmpty()) {
            escalateUnmatchedLegs(p, restored, unmatchedLegStops, triggerType, what, confidence, runId);
        }
        return new Result(Outcome.TRIMMED, qtyClosed, qtyRemaining, cr.avgFillPrice(), cr.orderRef());
    }

    /**
     * Accepted trim: repoint every OPEN leg named as a {@code replaces} target to its new id and
     * CLOSE every OPEN leg nothing replaced (its shares left with the trim). An empty restored list
     * means the provider did not touch the legs at all — they are left as they are.
     *
     * <p>Ruling F3: the close runs only when at least one OPEN leg was matched. A non-empty
     * restored list that matches NO leg says nothing reliable about which leg died; closing them
     * all would hand the next reconcile "no open leg, ≥1 closed" and book the position CLOSED
     * while the broker still holds the remainder. Then nothing is written and the stale stop ids
     * are returned for the caller to escalate.
     *
     * <p>Leg QUANTITIES are deliberately not written: the broker spreads a partial close across
     * its legs itself and reports only the total, so any per-leg split here would be our
     * arithmetic. {@code ReconcileService.syncLegQuantities} converges each surviving leg to its
     * own WORKING stop's qty on the next pass (moved from the old inline trim path).
     *
     * @return the stop ids of the OPEN legs left untouched because nothing matched; empty when the
     *         rows were reconciled (or there was nothing to reconcile)
     */
    List<String> repointLegsAfterAcceptedTrim(long positionId, List<RestoredLeg> restored) {
        if (restored.isEmpty()) return List.of();
        List<ExecutorPositionLeg> legs = legRepo.findOpenByPosition(positionId).stream()
                .filter(leg -> leg.stopOrderId() != null)
                .toList();
        boolean anyMatched = legs.stream().anyMatch(leg -> replacementFor(leg, restored) != null);
        if (!anyMatched) {
            return legs.stream().map(ExecutorPositionLeg::stopOrderId).toList();
        }
        Instant now = clock.instant();
        for (ExecutorPositionLeg leg : legs) {
            String replacement = replacementFor(leg, restored);
            if (replacement == null) {
                legRepo.closeLeg(leg.id(), null, "TRIM", now);
            } else if (!replacement.equals(leg.stopOrderId())) {
                legRepo.repointLegStop(leg.id(), replacement);
            }
        }
        return List.of();
    }

    /** Ruling F3: the broker trimmed and restored protection, but under ids that replace none of
     *  the recorded legs. The book (qty, trim_count) is already right; only the leg rows are
     *  stale, and nothing repairs them automatically — the ratchet will report the legs missing
     *  every pass. Logged and paged so an operator repoints them. */
    private void escalateUnmatchedLegs(ExecutorPosition p, List<RestoredLeg> restored,
            List<String> unmatchedLegStops, String triggerType, String what, Double confidence,
            String runId) {
        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("position_id", p.id());
        var legStops = inputs.putArray("leg_stop_order_ids");
        unmatchedLegStops.forEach(legStops::add);
        var restoredArr = inputs.putArray("protective_legs");
        for (RestoredLeg r : restored) {
            ObjectNode n = restoredArr.addObject();
            n.put("replaces", r.replaces());
            n.put("order_id", r.orderId());
        }
        String reasoning = what + " on position " + p.id() + " was accepted, but no restored "
                + "protective leg replaces a recorded leg " + unmatchedLegStops
                + " — leg rows left unchanged, operator must repoint them";
        decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(), triggerType,
                null, null, null, p.symbol(), inputs, null, "ESCALATE", "TRIM_LEGS_UNMATCHED", null,
                reasoning, confidence, null, null));
        telegram.notifyAlert(p.symbol(), "TRIM_LEGS_UNMATCHED", "CRITICAL", reasoning);
    }

    /**
     * Rejected trim (unchanged semantics, moved from ExecutorWebhookController): an OPEN leg whose
     * id no restored leg claims has its id NULLED — Agora's rollback can stop at the first failure,
     * so an unclaimed id is dead; a null id is a visible gap the ratchet reports.
     */
    void repointLegsAfterRejectedTrim(long positionId, List<RestoredLeg> restored) {
        for (ExecutorPositionLeg leg : legRepo.findOpenByPosition(positionId)) {
            if (leg.stopOrderId() == null) continue;
            String replacement = replacementFor(leg, restored);
            if (!leg.stopOrderId().equals(replacement)) {
                legRepo.repointLegStop(leg.id(), replacement);
            }
        }
    }

    private static String replacementFor(ExecutorPositionLeg leg, List<RestoredLeg> restored) {
        return restored.stream()
                .filter(r -> leg.stopOrderId().equals(r.replaces()))
                .map(RestoredLeg::orderId)
                .findFirst().orElse(null);
    }

    /** A broker VERDICT on a flatten (partial or full): repoint what the rollback touched, name
     *  the rejection, page on the unprotected case. Same vocabulary as HardTriggerService. */
    public void escalateRejected(ExecutorPosition p, BrokerRejectedException e, String triggerType,
            String what, Double confidence, String runId) {
        // Repoint only when Agora actually touched a leg: several reject codes fire BEFORE the
        // leg-cancel loop (INVALID_FRACTION, QTY_ROUNDED_TO_ZERO, CLOSE_ALREADY_PENDING, every
        // Alpaca rejection), and "not named" would then wrongly null live stop ids.
        boolean legCancelWasAttempted = (e.protectiveLegs() != null && !e.protectiveLegs().isEmpty())
                || "LEG_CANCEL_INCOMPLETE".equals(e.rejectCode())
                || "LEG_RESTORE_FAILED".equals(e.rejectCode())
                || "LEG_RESTORE_FAILED_UNPROTECTED".equals(e.rejectCode());
        if (legCancelWasAttempted) {
            List<RestoredLeg> restored = e.protectiveLegs() != null ? e.protectiveLegs() : List.of();
            positionRepo.repointStopLegs(p.id(), restored);
            repointLegsAfterRejectedTrim(p.id(), restored);
        }
        boolean positionAlreadyGone = AGORA_NO_POSITION.equals(e.rejectCode());
        String reasonCode = positionAlreadyGone ? "POSITION_ALREADY_GONE" : "BROKER_REJECTED";
        String reasoning = positionAlreadyGone
                ? "position already gone during " + what + ": " + e.getMessage()
                : "broker rejected " + what + " ["
                        + (e.rejectCode() == null ? "no reject code" : e.rejectCode())
                        + "]: " + e.getMessage();
        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("reject_code", e.rejectCode());
        decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(), triggerType,
                null, null, null, p.symbol(), inputs, null, "ESCALATE", reasonCode, null,
                reasoning, confidence, null, null));
        if ("LEG_RESTORE_FAILED_UNPROTECTED".equals(e.rejectCode())) {
            telegram.notifyAlert(p.symbol(), e.rejectCode(), "CRITICAL",
                    "partial close on " + p.symbol() + " was rejected and left the remaining "
                            + "position unprotected: " + e.getMessage());
        }
    }

    /** No verdict at all: the book is never touched. */
    public void escalateUnavailable(ExecutorPosition p, BrokerUnavailableException e,
            String triggerType, String what, Double confidence, String runId) {
        decisionLogRepo.insert(new DecisionLog(null, runId, ruleVersions.active(), triggerType,
                null, null, null, p.symbol(), null, null, "ESCALATE", "BROKER_UNAVAILABLE", null,
                "broker unavailable during " + what + ": " + e.getMessage(), confidence, null, null));
    }
}
