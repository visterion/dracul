package de.visterion.dracul.executor;

import java.util.List;

/**
 * Audit row: what the executor decided about one signal and why.
 *
 * <p>{@code action} names WHICH kind of decision this row is (the agent's
 * {@code SKIP}/{@code HOLD}/{@code ADD_TRANCHE} vocabulary from {@code submit_decision}). Without
 * it a HOLD on an open position and a SKIP of a pending signal are indistinguishable in the audit
 * trail: both are {@code accepted=false} with an empty {@code reject_reason}. It is nullable and
 * defaults to null for the code-gate rows written on the entry/exit paths, which carry their
 * meaning in {@code rejectReason} instead — hence the legacy 10-arg constructor below, which keeps
 * those call sites unchanged.
 */
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
        String createdAt,
        String action) {

    /** Legacy shape without an explicit action (code-gate rows). */
    public ExecutorDecision(Long id, String signalId, String symbol, boolean accepted,
            String rejectReason, List<String> vetoTrace, String rationale, String brokerOrderId,
            String runId, String createdAt) {
        this(id, signalId, symbol, accepted, rejectReason, vetoTrace, rationale, brokerOrderId,
                runId, createdAt, null);
    }
}
