package de.visterion.dracul.depot;

/** One executor decision (ENTER/ADD/TRIM/EXIT) in an open position's move timeline, carrying
 *  the {@code run_id} of the executor run that decided it — lets the frontend link each move to
 *  its raw Vistierie transcript (see {@link DepotController#transcript}). {@code createdAt} is
 *  the raw {@code decision_log.created_at} string (see {@link de.visterion.dracul.executor.DecisionLog}). */
public record DepotMove(String action, String reasonCode, String createdAt, String runId) {
}
