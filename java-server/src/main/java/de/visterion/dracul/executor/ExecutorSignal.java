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
        String preyId,
        java.time.LocalDate referenceBarDate,
        BigDecimal referenceAtr) {

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

    /**
     * Back-compat: the two counterfactual reference inputs default to null. Only
     * {@code PreySignalEmitter} sets them; operator injects via
     * {@code POST /api/executor/signals} deliberately carry neither, and rows written before
     * migration V49 have neither, so neither ever produces an {@code LLM_SKIP} counterfactual.
     */
    public ExecutorSignal(String signalId, String source, String agentVersion, String symbol, String direction,
            Double confidence, String mechanism, List<String> killCriteria, String horizon,
            BigDecimal referencePrice, String status, String createdAt, JsonNode thesis, String preyId) {
        this(signalId, source, agentVersion, symbol, direction, confidence, mechanism, killCriteria, horizon,
                referencePrice, status, createdAt, thesis, preyId, null, null);
    }
}
