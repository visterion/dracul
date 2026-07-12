package de.visterion.dracul.strigoi.spin;

/** A screened spin-off candidate — the deduped input to enrichment and to the
 *  {@code spin_candidate} ingestion write. */
public record SpinCandidate(
        String symbol,        // may be empty
        String companyName,
        String formType,
        String filingDate,    // ISO date string
        String filingUrl,
        String cik            // spin-co registrant CIK (natural-key head); null when not parseable
) {}
