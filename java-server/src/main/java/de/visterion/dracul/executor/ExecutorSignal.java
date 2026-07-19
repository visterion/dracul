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
        JsonNode thesis,
        String preyId) {

    /** Back-compat: thesis + preyId default to null. */
    public ExecutorSignal(String signalId, String source, String agentVersion, String symbol, String direction,
            Double confidence, String mechanism, List<String> killCriteria, String horizon,
            BigDecimal referencePrice, String status, String createdAt) {
        this(signalId, source, agentVersion, symbol, direction, confidence, mechanism, killCriteria, horizon,
                referencePrice, status, createdAt, null, null);
    }

    /** Back-compat: preyId defaults to null (thesis-carrying callers that predate prey linkage). */
    public ExecutorSignal(String signalId, String source, String agentVersion, String symbol, String direction,
            Double confidence, String mechanism, List<String> killCriteria, String horizon,
            BigDecimal referencePrice, String status, String createdAt, JsonNode thesis) {
        this(signalId, source, agentVersion, symbol, direction, confidence, mechanism, killCriteria, horizon,
                referencePrice, status, createdAt, thesis, null);
    }
}
