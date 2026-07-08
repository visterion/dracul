package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.util.List;

/** An injected piece of advice awaiting execution evaluation. */
public record ExecutorSignal(
        String signalId,
        String source,
        String agentVersion,
        String symbol,
        String direction,
        Double confidence,
        String mechanism,
        List<String> killCriteria,
        String horizon,
        BigDecimal referencePrice,
        String status,
        String createdAt) {
}
