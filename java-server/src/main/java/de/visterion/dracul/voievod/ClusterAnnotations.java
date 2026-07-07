package de.visterion.dracul.voievod;

import de.visterion.dracul.prey.Prey;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Deterministic, Dracul-domain annotations for a consensus cluster: which payoff families
 *  the contributing prey span, whether they cross families (the central "suspect" marker),
 *  and how far apart in time they were discovered ("same episode?"). All derived from
 *  Dracul's own Prey fields (anomalyType, discoveredAt) — no market data, no Agora. */
public record ClusterAnnotations(List<PayoffFamily> payoffFamilies, boolean crossFamily, long discoverySpreadDays) {

    public static ClusterAnnotations of(ConsensusCluster cluster) {
        var families = new LinkedHashSet<PayoffFamily>();
        var dates = new ArrayList<LocalDate>();
        for (Prey p : cluster.prey()) {
            families.add(PayoffFamily.of(p.anomalyType()));
            LocalDate d = safeDate(p.discoveredAt());
            if (d != null) dates.add(d);
        }
        long spread = 0;
        if (dates.size() >= 2) {
            LocalDate min = dates.stream().min(LocalDate::compareTo).orElseThrow();
            LocalDate max = dates.stream().max(LocalDate::compareTo).orElseThrow();
            spread = ChronoUnit.DAYS.between(min, max);
        }
        return new ClusterAnnotations(List.copyOf(families), families.size() > 1, spread);
    }

    /** Mirrors ConsensusDetector.safeDate: unparseable timestamps are skipped, never throw. */
    private static LocalDate safeDate(String discoveredAt) {
        try {
            return Horizons.dateOf(discoveredAt);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
