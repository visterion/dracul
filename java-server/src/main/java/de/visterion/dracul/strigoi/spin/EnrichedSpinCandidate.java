package de.visterion.dracul.strigoi.spin;

import java.math.BigDecimal;

/**
 * A tracked spin-off candidate rendered to the wire shape returned by the tool webhook, built
 * from a persisted {@code spin_candidate} row plus its per-stage JSONB snapshots (no live search).
 * No price on the candidate itself: the spin thesis is forced-selling / size.
 *
 * <p><b>Stage-gated, additive fields (all nullable until the row reaches the stage).</b> A row
 * progresses REGISTERED &rarr; WHEN_ISSUED &rarr; DISTRIBUTED (&rarr; SETTLED / ABANDONED), and
 * each stage fills its own block of fields; earlier stages' fields stay populated, later stages'
 * stay null. So a null field means "that stage's data is not yet available" — never a judgement.
 * The response set is limited to unpromoted {REGISTERED, WHEN_ISSUED, DISTRIBUTED} rows, so the
 * SETTLED valuation block is usually null here.
 *
 * <ul>
 *   <li><b>Base / term-sheet</b> — {@code symbol} (empty until the spin-co trades), {@code companyName},
 *       {@code formType}, {@code filingDate}, {@code filingUrl}, {@code termSheetAvailable}, and the
 *       server-extracted {@code distributionRatio}/{@code recordDate}/{@code distributionDate}. The raw
 *       {@code termSheet} text is NOT persisted by V26 (only {@code term_sheet_available}), so it is
 *       always null on the DB-backed payload — the structured terms carry the signal.</li>
 *   <li><b>{@code status}</b> — the lifecycle {@link SpinStatus} name.</li>
 *   <li><b>REGISTERED</b> — {@code totalAssets}, {@code totalLiabilities}, {@code retainedEarnings}
 *       (pre-distribution XBRL balance-sheet anchors, raw USD) and {@code industry}.</li>
 *   <li><b>DISTRIBUTED</b> — {@code spincoMarketCapMillions}, {@code parentMarketCapMillions},
 *       {@code sizeRatio}, {@code daysSinceDistribution}, {@code postSpinInsiderBuying}. The parent
 *       fields / {@code sizeRatio} are null when the parent could not be resolved (see
 *       {@link SpinCandidateEnricher}).</li>
 *   <li><b>SETTLED</b> — {@code priceToBook}, {@code evToEbit}, {@code fcfYield}.</li>
 * </ul>
 */
public record EnrichedSpinCandidate(
        String symbol,
        String companyName,
        String formType,
        String filingDate,
        String filingUrl,
        String termSheet,
        boolean termSheetAvailable,
        String distributionRatio,
        String recordDate,
        String distributionDate,
        // lifecycle
        String status,
        // REGISTERED stage
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal retainedEarnings,
        String industry,
        // DISTRIBUTED stage
        Double spincoMarketCapMillions,
        Double parentMarketCapMillions,
        Double sizeRatio,
        Integer daysSinceDistribution,
        Boolean postSpinInsiderBuying,
        // SETTLED stage
        Double priceToBook,
        Double evToEbit,
        Double fcfYield
) {}
