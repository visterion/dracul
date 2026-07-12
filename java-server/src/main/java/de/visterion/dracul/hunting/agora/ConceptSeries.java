package de.visterion.dracul.hunting.agora;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Neutral series of XBRL fact datapoints fetched from Agora (get_company_concept /
 * get_eps_history). A point with a null periodStart is an instant fact (e.g. total assets);
 * a point with both dates is a duration fact. No interpretation happens here.
 */
public record ConceptSeries(String tag, List<Point> points) {

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
