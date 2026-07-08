package de.visterion.dracul.executor;

import java.util.List;

/** Audit row: what the executor decided about one signal and why. */
public record ExecutorDecision(
        Long id,
        String signalId,
        String symbol,
        boolean accepted,
        String rejectReason,
        List<String> vetoTrace,
        String rationale,
        String brokerOrderId,
        String runId,
        String createdAt) {
}
