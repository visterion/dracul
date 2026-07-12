package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.ConceptSeries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Instant/annual anchoring helpers over Agora XBRL concept series, modelled directly on the
 * private helpers in {@link de.visterion.dracul.strigoi.lazarus.AltmanZCalculator} and shared by
 * the spin-lifecycle stage snapshotters. Pure and null-safe; no interpretation beyond picking the
 * latest instant, an instant at a given balance-sheet date, and the latest ~annual duration.
 *
 * <p>Unlike the Altman-Z calculator these helpers never enforce a single anchored balance-sheet
 * date across a set of concepts (no "all-or-nothing" contract): the snapshotters are additive and
 * fail-soft, so each concept degrades to null independently.
 */
final class SpinXbrlFacts {

    private static final long MIN_ANNUAL_DAYS = 350;
    private static final long MAX_ANNUAL_DAYS = 380;

    private SpinXbrlFacts() {}

    /** A dated XBRL value (instant end, or annual-duration period end). */
    record Dated(LocalDate end, BigDecimal value) {}

    /** Most recent instant point (null periodStart), by end; null if none. */
    static Dated latestInstant(ConceptSeries series) {
        Dated best = null;
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodEnd() == null || p.value() == null || p.periodStart() != null) continue;
            if (best == null || p.periodEnd().isAfter(best.end())) best = new Dated(p.periodEnd(), p.value());
        }
        return best;
    }

    /** Instant point at exactly {@code end} (the anchor balance-sheet date); null if none. */
    static BigDecimal instantAt(LocalDate end, ConceptSeries series) {
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodStart() != null || p.value() == null) continue;   // instant facts only
            if (end.equals(p.periodEnd())) return p.value();
        }
        return null;
    }

    /** Most recent ~annual (350-380d) duration point, by period end; null if none. */
    static Dated latestAnnualDuration(ConceptSeries series) {
        Dated best = null;
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodStart() == null || p.periodEnd() == null || p.value() == null) continue;
            long days = ChronoUnit.DAYS.between(p.periodStart(), p.periodEnd());
            if (days < MIN_ANNUAL_DAYS || days > MAX_ANNUAL_DAYS) continue;
            if (best == null || p.periodEnd().isAfter(best.end())) best = new Dated(p.periodEnd(), p.value());
        }
        return best;
    }
}
