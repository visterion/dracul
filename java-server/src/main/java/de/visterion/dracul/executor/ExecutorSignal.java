package de.visterion.dracul.executor;

import tools.jackson.databind.JsonNode;

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
        String createdAt,
        JsonNode thesis) {

    /**
     * Back-compat constructor for the many existing call sites (tests, other
     * production callers) that predate the {@code thesis} field. Defaults it
     * to {@code null} — no Prey thesis available.
     */
    public ExecutorSignal(String signalId, String source, String agentVersion, String symbol, String direction,
            Double confidence, String mechanism, List<String> killCriteria, String horizon,
            BigDecimal referencePrice, String status, String createdAt) {
        this(signalId, source, agentVersion, symbol, direction, confidence, mechanism, killCriteria, horizon,
                referencePrice, status, createdAt, null);
    }
}
