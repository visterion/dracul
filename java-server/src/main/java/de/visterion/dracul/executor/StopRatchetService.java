package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
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
    private final double chandelierMult;

    public StopRatchetService(
            ExecutionGateway gateway,
            ExecutorPositionRepository positionRepo,
            DecisionLogRepository decisionRepo,
            RuleVersionProvider ruleVersions,
            StopRatchetGuard guard,
            ObjectMapper mapper,
            @Value("${dracul.executor.chandelier-mult:3.0}") double chandelierMult) {
        this.gateway = gateway;
        this.positionRepo = positionRepo;
        this.decisionRepo = decisionRepo;
        this.ruleVersions = ruleVersions;
        this.guard = guard;
        this.mapper = mapper;
        this.chandelierMult = chandelierMult;
    }

    public void ratchet(List<ExecutorPosition> openPositions, Map<String, BigDecimal> atrBySymbol,
            String runId) {
        for (ExecutorPosition p : openPositions) {
            if (p.highestPrice() == null) continue;
            BigDecimal atr = atrBySymbol.get(p.symbol());
            if (atr == null) continue;

            BigDecimal chandelier = computeChandelier(p, atr);
            if (!guard.permit(p.activeStop(), chandelier, p.side())) continue;

            String orderId = p.stopOrderId() != null ? p.stopOrderId() : p.brokerOrderId();
            try {
                gateway.modifyBracket(p.connection(), orderId, p.symbol(), chandelier, null);
            } catch (BrokerUnavailableException e) {
                decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                        "MAINTENANCE", null, null, null, p.symbol(), null, null,
                        "ESCALATE", "BROKER_UNAVAILABLE", null,
                        "broker unavailable during stop-ratchet modify: " + e.getMessage(),
                        null, null, null));
                continue;
            }

            positionRepo.updateMaintenance(p.id(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                    chandelier, null);

            recordRatchet(p, atr, chandelier, runId);
        }
    }

    private BigDecimal computeChandelier(ExecutorPosition p, BigDecimal atr) {
        BigDecimal offset = atr.multiply(BigDecimal.valueOf(chandelierMult));
        return "SELL".equals(p.side())
                ? p.highestPrice().add(offset)
                : p.highestPrice().subtract(offset);
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
}
