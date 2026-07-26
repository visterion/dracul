package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Trailing chandelier stop: raises (BUY) or lowers (SELL) the active stop toward the market as
 * a position runs in its favor, but never moves it against the position. {@link StopRatchetGuard}
 * is the single choke point enforcing that — this service must never call the gateway or update
 * the position book when the guard denies the move.
 *
 * <p>On {@link BrokerUnavailableException} during {@code modifyBracket}, this escalates via the
 * decision log and leaves the old stop in place — mirrors {@link ReconcileService} and
 * {@link HardTriggerService}'s idiom.
 *
 * <p>When a position has added a second tranche ({@link ExecutorPosition#tranche2StopOrderId()}
 * non-null), the same chandelier level is sent to <em>both</em> stop legs — they share one
 * position and must ratchet in lockstep. If the second leg's {@code modifyBracket} call throws
 * mid-loop after the first leg already succeeded at the broker, this still escalates and skips
 * persisting the new stop for this pass; the next maintenance run will retry both legs.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class StopRatchetService {

    private final ExecutionGateway gateway;
    private final ExecutorPositionRepository positionRepo;
    private final DecisionLogRepository decisionRepo;
    private final RuleVersionProvider ruleVersions;
    private final StopRatchetGuard guard;
    private final ObjectMapper mapper;
    private final ExecutorNotifier executorNotifier;
    private final double chandelierMult;

    public StopRatchetService(
            ExecutionGateway gateway,
            ExecutorPositionRepository positionRepo,
            DecisionLogRepository decisionRepo,
            RuleVersionProvider ruleVersions,
            StopRatchetGuard guard,
            ObjectMapper mapper,
            ExecutorNotifier executorNotifier,
            @Value("${dracul.executor.chandelier-mult:3.0}") double chandelierMult) {
        this.gateway = gateway;
        this.positionRepo = positionRepo;
        this.decisionRepo = decisionRepo;
        this.ruleVersions = ruleVersions;
        this.guard = guard;
        this.mapper = mapper;
        this.executorNotifier = executorNotifier;
        this.chandelierMult = chandelierMult;
    }

    public void ratchet(List<ExecutorPosition> openPositions, Map<String, BigDecimal> atrBySymbol,
            Map<String, BigDecimal> closeBySymbol, String runId) {
        for (ExecutorPosition p : openPositions) {
            if (p.highestPrice() == null) continue;
            BigDecimal atr = atrBySymbol.get(p.symbol());
            if (atr == null) continue;

            BigDecimal chandelier = computeChandelier(p, atr);
            if (!guard.permit(p.activeStop(), chandelier, p.side())) continue;

            // The guard only compares against the OLD stop, never against the market. If the price
            // fell more than chandelier-mult x ATR off the high while staying above the hard stop,
            // the chandelier sits on the wrong side of the market. Skip silently — the soft trigger
            // owns that state. The reference is a bar close, not a live quote, so this reduces the
            // risk rather than eliminating it; the executor runs after the US close, so the gap is
            // small. Placed BEFORE the escalations below: only what was actually sendable escalates.
            BigDecimal price = closeBySymbol.get(p.symbol());
            if (price == null) continue;
            boolean safeSide = "SELL".equals(p.side())
                    ? chandelier.compareTo(price) > 0
                    : chandelier.compareTo(price) < 0;
            if (!safeSide) continue;

            if (p.tranche() >= 2 || p.tranche2OrderId() != null || p.tranche2StopOrderId() != null) {
                // A tranche-2 position holds TWO stop legs at the broker. Post-fill both entry ids
                // are gone, so both modify calls would land in Agora's symbol fallback, which keeps
                // only the LAST stop leg it finds on the Uic — one leg patched twice, the other
                // never, while modifyBracket still reports accepted. Dracul would then write the new
                // stop into the book and stopguard would trust it, with half the position actually
                // sitting on the old stop. A silent partial success is worse than a loud failure, so
                // this escalates and leaves the stop where it is.
                //
                // `tranche` is the RELIABLE marker: Saxo returns no leg ids, so tranche2StopOrderId
                // is null by design there (see the "Saxo/live brackets expose no leg ids" comment in
                // ExecutorWebhookController's adoption branch) and a gate keyed on the leg id alone
                // would never fire — precisely on the broker this matters for. The two id fields
                // stand alongside as belt-and-braces, for brokers that do report them.
                escalate(p, runId, "TRANCHE_RATCHET_UNSUPPORTED",
                        "stop ratchet unsupported while a tranche 2 is open: two stop legs cannot be "
                                + "addressed unambiguously through modifyBracket");
                continue;
            }

            BigDecimal oldStop = p.activeStop();

            // Agora resolves the stop leg FROM the bracket id — pre-fill through the parent's
            // embedded RelatedOpenOrders, post-fill through the by-Uic symbol fallback. Handing it
            // the stop LEG id instead fails in both phases: pre-fill the leg isn't a top-level
            // order at all, post-fill it is found but its Oco sibling is the take-profit, so Agora
            // reports "no stop-loss leg". That is why the ratchet never moved a single stop between
            // 2026-07-19 and 2026-07-26. Do NOT "restore" stopOrderId here.
            String bracketId = p.brokerOrderId();
            if (bracketId == null) {
                escalate(p, runId, "NO_BRACKET_ID",
                        "stop ratchet cannot address the bracket: broker_order_id is null");
                continue;
            }
            try {
                gateway.modifyBracket(p.connection(), bracketId, p.symbol(), chandelier, null);
            } catch (BrokerUnavailableException e) {
                escalate(p, runId, "BROKER_UNAVAILABLE",
                        "broker unavailable during stop-ratchet modify: " + e.getMessage());
                continue;
            }

            positionRepo.updateMaintenance(p.id(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                    chandelier, null);

            recordRatchet(p, atr, chandelier, runId);

            executorNotifier.notifyStopRatchet(p, oldStop, chandelier, p.connection());
        }
    }

    /**
     * Chandelier level, rounded to two decimals toward the SAFE side: FLOOR for a long (the stop is
     * never raised beyond the computed level), CEILING for a short.
     *
     * <p>Deliberately FLOOR/CEILING and not DOWN/UP — the latter round toward and away from zero,
     * which inverts the intent for a negative level (a huge ATR on a cheap instrument). The guard
     * would reject such a value anyway; this simply says what is meant.
     *
     * <p>Rounding happens HERE, before {@link StopRatchetGuard#permit}, so the value checked, the
     * value sent to the broker and the value written to the book are one and the same — and so a
     * sub-cent improvement is denied instead of producing an empty modify every run.
     */
    private BigDecimal computeChandelier(ExecutorPosition p, BigDecimal atr) {
        BigDecimal offset = atr.multiply(BigDecimal.valueOf(chandelierMult));
        return "SELL".equals(p.side())
                ? p.highestPrice().add(offset).setScale(2, RoundingMode.CEILING)
                : p.highestPrice().subtract(offset).setScale(2, RoundingMode.FLOOR);
    }

    private void recordRatchet(ExecutorPosition p, BigDecimal atr, BigDecimal chandelier, String runId) {
        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("highest_price", p.highestPrice());
        inputs.put("atr", atr);
        inputs.put("chandelier_mult", chandelierMult);
        inputs.put("old_stop", p.activeStop());
        inputs.put("new_stop", chandelier);

        String basisSide = "SELL".equals(p.side()) ? "lowestLow + " : "highestHigh - ";
        ObjectNode order = mapper.createObjectNode();
        order.put("stop_basis", "chandelier: " + basisSide + chandelierMult + "xATR");
        order.put("new_stop", chandelier);

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                "MODIFY_STOP", null, order, null, null, null, null));
    }

    /**
     * One non-throwing escalation row. Carries the signal and agent attribution the position knows,
     * matching {@link EntryExpiryService}'s idiom — an operator triaging {@code decision_log} needs
     * to know which signal and which agent put this position on the book.
     */
    private void escalate(ExecutorPosition p, String runId, String reasonCode, String reasoning) {
        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", p.sourceSignalId(), p.sourceAgent(), null, p.symbol(), null, null,
                "ESCALATE", reasonCode, null, reasoning,
                null, null, null));
    }
}
