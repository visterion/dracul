package de.visterion.dracul.strigoi.merger;

import java.util.List;

/**
 * The merger enrichment's output PLUS the degradations the enrichment itself introduced.
 *
 * <p>The enrichment used to return a bare {@code List}, which gave it no channel to influence the
 * fetch health — that health came exclusively from {@code searchMergers}. Two real losses were
 * therefore invisible: the candidate cap dropping the tail of the screened list (always the OLDEST
 * deals, since EFTS returns file_date DESC), and a {@code get_filing_text} that failed so the deal
 * terms could not be parsed for that candidate. Both were reported as
 * {@code partial=false truncated=false status=healthy}.
 *
 * @param candidates         the enriched candidates actually returned
 * @param truncated          true when the cap cut the screened list
 * @param filingTextFailures how many candidates came back without their term sheet (both kinds)
 * @param oversizedFilings   how many of those failures were Agora refusing an OVERSIZED document
 *                           rather than an outage — a per-document property that will fail again
 *                           on every retry, so it reads differently from a transient failure
 */
public record EnrichedMergerBatch(List<EnrichedMergerCandidate> candidates,
                                  boolean truncated,
                                  int filingTextFailures,
                                  int oversizedFilings) {

    /** True when anything about this batch is worth putting into the fetch health. */
    public boolean degraded() { return truncated || filingTextFailures > 0; }
}
