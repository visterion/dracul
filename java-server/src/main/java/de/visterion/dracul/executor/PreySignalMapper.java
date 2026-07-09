package de.visterion.dracul.executor;

import de.visterion.dracul.prey.Prey;

import java.util.List;
import java.util.UUID;

/**
 * Deterministic adapter turning a persisted {@link Prey} finding into a pending
 * {@link ExecutorSignal}. Pure function — no side effects, no persistence.
 *
 * <p>The mapping mirrors the operator inject seam in
 * {@link ExecutorSignalController#inject}: a fresh random {@code signalId}, a
 * literal {@code PENDING} status, and a {@code null} {@code createdAt} (the DB
 * column defaults to {@code now()}).
 */
public class PreySignalMapper {

    public ExecutorSignal map(Prey p) {
        return new ExecutorSignal(
                UUID.randomUUID().toString(), // same generation as the inject seam
                p.discoveredBy(),
                null,                          // agentVersion — not carried on Prey
                p.symbol(),
                // All six anomalies (spin-off, insider cluster, PEAD, index-inclusion,
                // M&A arb, quality-at-52w-low) are long-biased, so direction is a
                // constant BUY. Per-type direction nuance is a deferred fast-follow.
                "BUY",
                p.confidence(),
                p.anomalyType(),
                List.of(),                     // killCriteria — derived downstream, empty here
                p.horizon(),
                null,                          // referencePrice — resolved at execution time
                "PENDING",                     // matches the inject seam's literal status
                null);                         // createdAt — DB defaults to now(), like inject
    }
}
