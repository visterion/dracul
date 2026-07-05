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

    /** One reported datapoint. periodStart/periodEnd may be null (provider omission). */
    public record Point(LocalDate periodStart, LocalDate periodEnd, BigDecimal value) {}

    public static ConceptSeries empty(String tag) {
        return new ConceptSeries(tag, List.of());
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }
}
