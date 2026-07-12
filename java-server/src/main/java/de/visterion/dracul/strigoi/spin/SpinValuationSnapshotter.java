package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * SETTLED-stage enrichment: the fundamental re-rating read available once the spin-off files its
 * first standalone report and Finnhub coverage catches up. Pure and fail-soft. This is a small,
 * purpose-built extraction — deliberately NOT the Altman-Z five-ratio machine.
 *
 * <ul>
 *   <li><b>priceToBook</b> — read straight from the Finnhub fundamentals blob key {@code pbAnnual}.</li>
 *   <li><b>fcfYield</b> — the reciprocal of the Finnhub {@code pfcfShareTTM} (price / FCF-per-share)
 *       key, i.e. FCF yield; null when the key is absent or non-positive.</li>
 *   <li><b>evToEbit</b> — the one multiple Finnhub does not expose, so it is computed from a coarse,
 *       upward-biased book enterprise-value proxy: {@code EV = marketCap + totalLiabilities − cash}
 *       (Finnhub {@code marketCapitalization} in USD millions converted to raw USD, plus the XBRL
 *       total liabilities, minus XBRL {@code CashAndCashEquivalentsAtCarryingValue}) divided by EBIT
 *       (the latest ~annual XBRL {@code OperatingIncomeLoss}, the same tag the Altman-Z uses). Using
 *       total liabilities in place of pure interest-bearing debt is a KNOWN upward bias that is left
 *       in deliberately — isolating debt cleanly from XBRL is unreliable. Cash is netted out
 *       fail-soft: when the cash concept is missing the EV keeps the (larger) no-cash value rather
 *       than aborting. Null unless market cap, liabilities and a positive EBIT are all present.</li>
 *   <li><b>bookValue</b> — the hard-data companion to Finnhub's P/B: XBRL {@code Assets} −
 *       {@code Liabilities} at the latest anchored balance-sheet date (raw USD). Reliable via the
 *       CIK-based concept fetch even while Finnhub still lags a freshly-settled ticker.</li>
 * </ul>
 *
 * <p><b>Source-down signalling.</b> The XBRL concept fetch is the strict, CIK-capable variant, so an
 * {@link de.visterion.dracul.marketdata.AgoraUnavailableException} PROPAGATES (not caught here) for
 * the E3 {@code markIfSourceDown} guard; missing concepts degrade to null (no throw). The Finnhub
 * fundamentals blob is read through the swallowing {@link AgoraCompanyData#fundamentals(String)},
 * which absorbs outages as a null blob (its ratios degrade to null) and can never trip the batch guard.
 */
@Component
public class SpinValuationSnapshotter {

    private static final BigDecimal USD_PER_MILLION = BigDecimal.valueOf(1_000_000L);
    private static final int RATIO_SCALE = 4;

    private final AgoraFilings filings;
    private final AgoraCompanyData companyData;

    public SpinValuationSnapshotter(AgoraFilings filings, AgoraCompanyData companyData) {
        this.filings = filings;
        this.companyData = companyData;
    }

    /** Post-settlement valuation snapshot; {@code bookValue} is raw USD, the ratios are decimals. */
    public record SpinValuationSnapshot(
            Double priceToBook,
            Double evToEbit,
            Double fcfYield,
            BigDecimal bookValue,
            boolean available) {

        static SpinValuationSnapshot unavailable() {
            return new SpinValuationSnapshot(null, null, null, null, false);
        }
    }

    /**
     * @param symbol the settled spin-off ticker (Finnhub fundamentals + industry key).
     * @param cik    the spin-co registrant CIK (XBRL concept fetch key).
     */
    public SpinValuationSnapshot snapshot(String symbol, String cik) {
        // XBRL (strict, propagates an outage): book value + EBIT.
        SpinXbrlFacts.Dated assets = SpinXbrlFacts.latestInstant(filings.conceptStrict(symbol, cik, "Assets"));
        BigDecimal liabilities = null;
        BigDecimal cash = null;
        if (assets != null) {
            liabilities = SpinXbrlFacts.instantAt(assets.end(), filings.conceptStrict(symbol, cik, "Liabilities"));
            // Cash is netted into EV fail-soft, anchored to the same balance-sheet date as liabilities.
            cash = SpinXbrlFacts.instantAt(assets.end(),
                    filings.conceptStrict(symbol, cik, "CashAndCashEquivalentsAtCarryingValue"));
        }
        BigDecimal bookValue = (assets != null && liabilities != null)
                ? assets.value().subtract(liabilities) : null;
        SpinXbrlFacts.Dated ebit =
                SpinXbrlFacts.latestAnnualDuration(filings.conceptStrict(symbol, cik, "OperatingIncomeLoss"));

        // Finnhub (swallowing, null blob on outage): direct ratios + market cap.
        JsonNode metrics = companyData.fundamentals(symbol);
        Double priceToBook = dbl(metrics, "pbAnnual");
        Double marketCapMillions = dbl(metrics, "marketCapitalization");
        Double pfcfShare = dbl(metrics, "pfcfShareTTM");
        Double fcfYield = (pfcfShare != null && pfcfShare > 0)
                ? BigDecimal.ONE.divide(BigDecimal.valueOf(pfcfShare), 6, RoundingMode.HALF_UP).doubleValue()
                : null;

        Double evToEbit = null;
        if (marketCapMillions != null && liabilities != null && ebit != null && ebit.value().signum() > 0) {
            BigDecimal marketCapUsd = BigDecimal.valueOf(marketCapMillions).multiply(USD_PER_MILLION);
            BigDecimal enterpriseValue = marketCapUsd.add(liabilities);
            if (cash != null) enterpriseValue = enterpriseValue.subtract(cash);   // fail-soft cash netting
            evToEbit = enterpriseValue.divide(ebit.value(), RATIO_SCALE, RoundingMode.HALF_UP).doubleValue();
        }

        boolean available = priceToBook != null || evToEbit != null || fcfYield != null || bookValue != null;
        return new SpinValuationSnapshot(priceToBook, evToEbit, fcfYield, bookValue, available);
    }

    /** Numeric Finnhub metric key; null on a null/non-object blob or an absent/non-numeric field. */
    private static Double dbl(JsonNode metrics, String field) {
        if (metrics == null || !metrics.isObject()) return null;
        JsonNode n = metrics.path(field);
        return n.isNumber() ? n.asDouble() : null;
    }
}
