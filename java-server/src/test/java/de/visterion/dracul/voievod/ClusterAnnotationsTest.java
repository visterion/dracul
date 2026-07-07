package de.visterion.dracul.voievod;

import de.visterion.dracul.prey.Prey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterAnnotationsTest {

    private Prey prey(String discoveredBy, String anomalyType, String discoveredAt) {
        return new Prey("id-" + discoveredBy, "SYM", "Sym Corp", anomalyType,
                0.7, "thesis", List.of(), List.of(), "6m", discoveredBy, discoveredAt);
    }

    private ConsensusCluster cluster(Prey... prey) {
        return new ConsensusCluster("SYM", "Sym Corp", List.of(prey));
    }

    @Test
    void singleFamilyIsNotCrossFamily() {
        var a = ClusterAnnotations.of(cluster(
                prey("strigoi-spin", "SPINOFF", "2026-06-01T00:00:00Z"),
                prey("strigoi-insider", "INSIDER_CLUSTER", "2026-06-03T00:00:00Z")));
        assertThat(a.crossFamily()).isFalse();
        assertThat(a.payoffFamilies()).containsExactly(PayoffFamily.DRIFT);
        assertThat(a.discoverySpreadDays()).isEqualTo(2);
    }

    @Test
    void mixedFamiliesAreCrossFamily() {
        var a = ClusterAnnotations.of(cluster(
                prey("strigoi-lazarus", "QUALITY_52W_LOW", "2026-06-01T00:00:00Z"),
                prey("strigoi-merger", "MERGER_ARB", "2026-06-01T00:00:00Z")));
        assertThat(a.crossFamily()).isTrue();
        assertThat(a.payoffFamilies()).containsExactlyInAnyOrder(PayoffFamily.DRIFT, PayoffFamily.EVENT);
        assertThat(a.discoverySpreadDays()).isZero();
    }

    @Test
    void unknownCountsAsDistinctFamilyConservatively() {
        var a = ClusterAnnotations.of(cluster(
                prey("strigoi-spin", "SPINOFF", "2026-06-01T00:00:00Z"),
                prey("mystery", "WEIRD_TYPE", "2026-06-05T00:00:00Z")));
        assertThat(a.crossFamily()).isTrue();
        assertThat(a.payoffFamilies()).containsExactlyInAnyOrder(PayoffFamily.DRIFT, PayoffFamily.UNKNOWN);
        assertThat(a.discoverySpreadDays()).isEqualTo(4);
    }

    @Test
    void unparseableDateIsSkippedFromSpreadAndNeverThrows() {
        var a = ClusterAnnotations.of(cluster(
                prey("strigoi-spin", "SPINOFF", "garbage"),
                prey("strigoi-insider", "INSIDER_CLUSTER", "2026-06-05T00:00:00Z")));
        assertThat(a.discoverySpreadDays()).isZero();
        assertThat(a.crossFamily()).isFalse();
    }
}
