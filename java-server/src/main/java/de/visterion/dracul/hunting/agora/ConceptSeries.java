package de.visterion.dracul.hunting.agora;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Neutral series of XBRL fact datapoints fetched from Agora (get_company_concept /
 * get_eps_history). A point with a null periodStart is an instant fact (e.g. total assets);
 * a point with both dates is a duration fact. No interpretation happens here.
 */
public record ConceptSeries(String tag, List<Point> points) {

    /**
     * A batch of {@link FundamentalConcept} series fetched in ONE {@code get_fundamental_concepts}
     * call (the non-US Altman-Z path), each with its reporting {@code unit} (ISO-4217 currency).
     * A requested concept the company never filed is present as an EMPTY series with a null unit,
     * mirroring {@code companyFactsStrict}'s "absent tag => empty, not missing" contract.
     */
    public record MultiConcept(Map<FundamentalConcept, ConceptSeries> series,
                               Map<FundamentalConcept, String> units) {
        /** Series for {@code concept}; an empty series (never null) when the concept was not filed. */
        public ConceptSeries series(FundamentalConcept concept) {
            return series.getOrDefault(concept, ConceptSeries.empty(concept.name()));
        }

        /** Reporting-currency code the concept's values are expressed in; null when absent. */
        public String unit(FundamentalConcept concept) {
            return units.get(concept);
        }
    }

    /** One reported datapoint. periodStart/periodEnd may be null (provider omission).
     *  {@code filed} is the date the datapoint was reported to EDGAR (Agora already returns it);
     *  it may be null when the provider omits it, and is needed by the spin-lifecycle
     *  SETTLED detection (first standalone report filed after the distribution). */
    public record Point(LocalDate periodStart, LocalDate periodEnd, BigDecimal value, LocalDate filed) {
        /** Back-compat convenience for callers/tests that don't track the filing date. */
        public Point(LocalDate periodStart, LocalDate periodEnd, BigDecimal value) {
            this(periodStart, periodEnd, value, null);
        }
    }

    public static ConceptSeries empty(String tag) {
        return new ConceptSeries(tag, List.of());
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }
}
