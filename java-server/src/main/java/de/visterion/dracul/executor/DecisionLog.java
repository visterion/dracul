package de.visterion.dracul.executor;

import tools.jackson.databind.JsonNode;

/** One row of the rich decision-log audit trail: what the executor decided and why. */
public record DecisionLog(
        String logId,
        String runId,
        String ruleVersion,
        String triggerType,
        String signalId,
        String sourceAgent,
        String sourceAgentVersion,
        String symbol,
        JsonNode inputsSnapshot,
        JsonNode vetoResults,
        String action,
        String reasonCode,
        JsonNode orderJson,
        String reasoning,
        Double confidenceInDecision,
        JsonNode latency,
        String createdAt) {
}
