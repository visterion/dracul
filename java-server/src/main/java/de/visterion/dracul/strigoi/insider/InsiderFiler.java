package de.visterion.dracul.strigoi.insider;

import java.math.BigDecimal;

/**
 * One distinct insider in a cluster.
 *
 * <p>{@code role} is Agora's free-text Form-4 officer title (e.g. "Chief Executive Officer");
 * empty when the filer is not an officer (typically a director or 10% owner).
 *
 * <p>The remaining fields are owner-history enrichment, filled in by
 * {@link InsiderEnrichmentService} from Agora's {@code get_form4_owner_history} and null/UNKNOWN
 * until then (the deterministic screener emits {@link #unclassified}):
 * <ul>
 *   <li>{@code classification} — routine/opportunistic/unknown per {@link FilerClassification}
 *       (Cohen, Malloy &amp; Pomorski 2012). UNKNOWN until classified and whenever the history is
 *       too thin/incomplete or the owner could not be matched.</li>
 *   <li>{@code sharesOwnedFollowing} — shares held after the filer's most recent purchase in the
 *       cluster window (from the owner history); null when unavailable.</li>
 *   <li>{@code purchaseAsPctOfHoldings} — the filer's cluster-window purchase shares divided by
 *       {@code sharesOwnedFollowing}, a relative-conviction gauge (decimal fraction); null when
 *       {@code sharesOwnedFollowing} is null/zero.</li>
 *   <li>{@code planned10b5_1} — tri-state: TRUE if any cluster-window purchase carried the Rule
 *       10b5-1(c) plan flag (non-discretionary), FALSE if all carried an explicit false, null when
 *       unknown (pre-2023 filings). null is NOT false.</li>
 * </ul>
 */
public record InsiderFiler(
        String name,
        String role,
        FilerClassification classification,
        BigDecimal sharesOwnedFollowing,
        BigDecimal purchaseAsPctOfHoldings,
        Boolean planned10b5_1
) {

    /** Screener-stage filer: classification UNKNOWN and owner-history fields null until
     *  {@link InsiderEnrichmentService} fills them in. */
    public static InsiderFiler unclassified(String name, String role) {
        return new InsiderFiler(name, role, FilerClassification.UNKNOWN, null, null, null);
    }

    /** Copy with the owner-history-derived fields set (name/role preserved). */
    public InsiderFiler withClassification(FilerClassification classification,
                                           BigDecimal sharesOwnedFollowing,
                                           BigDecimal purchaseAsPctOfHoldings,
                                           Boolean planned10b5_1) {
        return new InsiderFiler(name, role, classification,
                sharesOwnedFollowing, purchaseAsPctOfHoldings, planned10b5_1);
    }
}
