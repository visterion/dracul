package de.visterion.dracul.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Pure ratchet guard: a stop may only ever move in the position's favor.
 *
 * <p>For a BUY (long), the stop may only be raised; for a SELL (short), the stop may only be
 * lowered. Anything else — an unknown/null side, or a proposed stop that doesn't improve on the
 * current one — is denied. This is the single choke point that guarantees zero down-moves.
 */
@Component
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class StopRatchetGuard {

    public boolean permit(BigDecimal current, BigDecimal proposed, String side) {
        if (current == null || proposed == null || side == null) return false;
        if ("BUY".equalsIgnoreCase(side)) {
            return proposed.compareTo(current) > 0;
        }
        if ("SELL".equalsIgnoreCase(side)) {
            return proposed.compareTo(current) < 0;
        }
        return false;
    }
}
