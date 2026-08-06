package de.visterion.dracul.strigoi.insider;

import java.util.List;

/**
 * The insider enrichment's output PLUS the degradations the enrichment itself introduced.
 *
 * <p>The enrichment used to return a bare {@code List}, so it had no channel to the fetch health —
 * which came exclusively from {@code recentForm4}. That is how the 2026-08-06 production run could
 * disable both the OHLC and the owner-history source after one cluster each, skip enrichment for
 * every remaining cluster, and still report
 * {@code items=1 (partial=false truncated=false status=healthy)}. Mirrors
 * {@code EnrichedMergerBatch}, which exists for the same reason.
 *
 * @param clusters         the enriched clusters actually returned
 * @param truncated        true when the cap cut the screened cluster list
 * @param degradedClusters how many clusters lost at least one enrichment source — whether from a
 *                         per-cluster lookup failure or because the source was already down
 */
public record EnrichedInsiderBatch(List<EnrichedInsiderCluster> clusters,
                                   boolean truncated,
                                   int degradedClusters) {

    /** True when anything about this batch is worth putting into the fetch health. */
    public boolean degraded() { return truncated || degradedClusters > 0; }
}
