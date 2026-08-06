package de.visterion.dracul.strigoi.lazarus;

import java.util.List;

/**
 * The lazarus enrichment's output PLUS the degradations the enrichment itself introduced.
 *
 * <p>The enrichment used to return a bare {@code List}, which gave it no channel to the fetch
 * health for a loss that does NOT change the list's size: a candidate that came back WITHOUT its
 * F-score, timing signals, Altman-Z or forward revisions is still a candidate, so the controller's
 * {@code enrichmentDropped = screened.size() - enriched.size()} counts it as zero. Candidates that
 * vanish (the accruals hard-drop, the enrichment cap) stay on that existing counter — this record
 * carries only what it structurally cannot see.
 *
 * <p>Mirrors {@code EnrichedInsiderBatch} and {@code EnrichedMergerBatch}, which exist for the
 * same reason. It has no {@code truncated} field on purpose: the lazarus cap is a size change and
 * is therefore already inside {@code enrichmentDropped}.
 *
 * @param candidates         the enriched candidates actually returned
 * @param degradedCandidates how many of them lost at least one enrichment source — whether from
 *                           their own failed lookup or because the source was already down
 */
public record EnrichedLazarusBatch(List<EnrichedLazarusCandidate> candidates,
                                   int degradedCandidates) {
}
