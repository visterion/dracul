package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * REGISTERED-stage enrichment: the spin-off's pre-distribution balance sheet, fetched by
 * registrant CIK (a ticker does not yet exist at 10-12B time). Pure and fail-soft — this is a
 * snapshot producer with no market-cap ratios (there is no market capitalisation before the
 * distribution), so it is NOT the Altman-Z calculator: it only surfaces the three raw balance
 * sheet anchors plus the Finnhub industry when a ticker happens to exist.
 *
 * <p>Balance-sheet concepts are read via {@link AgoraFilings#conceptStrict(String, String, String)}
 * (CIK-capable) and instant-anchored to the latest reported {@code Assets} date using
 * {@link SpinXbrlFacts}; {@code Liabilities} and {@code RetainedEarningsAccumulatedDeficit} must
 * share that date or they degrade to null (no cross-date mixing). Each concept degrades to null
 * independently — a missing one never zeroes the others.
 *
 * <p><b>Source-down signalling.</b> The concept fetch is the strict variant, so an
 * {@link de.visterion.dracul.marketdata.AgoraUnavailableException} (EDGAR/Agora outage) PROPAGATES
 * — deliberately not caught here, exactly like {@code AltmanZCalculator} — so the E3 reconciler can
 * apply its {@code markIfSourceDown} guard and short-circuit the concept source for the rest of the
 * batch. "No data for this CIK" comes back as an empty series and degrades to null fields (no throw).
 * The industry lookup goes through the swallowing {@link AgoraCompanyData#profile(String)}, which
 * absorbs outages internally (null on any failure) — it can never trip the batch guard, matching how
 * the echo/insider hunters treat the profile blob.
 */
@Component
public class SpinBalanceSheetSnapshotter {

    private final AgoraFilings filings;
    private final AgoraCompanyData companyData;

    public SpinBalanceSheetSnapshotter(AgoraFilings filings, AgoraCompanyData companyData) {
        this.filings = filings;
        this.companyData = companyData;
    }

    /** Pre-distribution balance-sheet anchors. {@code available} is true once the balance sheet
     *  resolves (total assets present); {@code industry} is an independent best-effort field. */
    public record SpinBalanceSheetSnapshot(
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal retainedEarnings,
            String industry,
            boolean available) {

        static SpinBalanceSheetSnapshot unavailable() {
            return new SpinBalanceSheetSnapshot(null, null, null, null, false);
        }
    }

    /**
     * @param symbol the spin-co ticker if one already exists (usually blank at REGISTERED time);
     *               used only for the industry lookup, never required for the balance sheet.
     * @param cik    the spin-co registrant CIK — the primary key for the XBRL fetch.
     */
    public SpinBalanceSheetSnapshot snapshot(String symbol, String cik) {
        SpinXbrlFacts.Dated assets = SpinXbrlFacts.latestInstant(filings.conceptStrict(symbol, cik, "Assets"));

        BigDecimal totalAssets = assets == null ? null : assets.value();
        BigDecimal totalLiabilities = null;
        BigDecimal retainedEarnings = null;
        if (assets != null) {
            totalLiabilities = SpinXbrlFacts.instantAt(assets.end(),
                    filings.conceptStrict(symbol, cik, "Liabilities"));
            retainedEarnings = SpinXbrlFacts.instantAt(assets.end(),
                    filings.conceptStrict(symbol, cik, "RetainedEarningsAccumulatedDeficit"));
        }

        String industry = industry(symbol);
        boolean available = totalAssets != null;
        return new SpinBalanceSheetSnapshot(totalAssets, totalLiabilities, retainedEarnings, industry, available);
    }

    /** Finnhub industry — only when a ticker exists; swallowing lookup, null on any failure. */
    private String industry(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        JsonNode profile = companyData.profile(symbol);
        if (profile == null) return null;
        JsonNode ind = profile.path("finnhubIndustry");
        return ind.isTextual() ? ind.asText() : null;
    }
}
