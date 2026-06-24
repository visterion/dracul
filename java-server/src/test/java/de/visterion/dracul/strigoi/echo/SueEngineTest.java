package de.visterion.dracul.strigoi.echo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SueEngineTest {

    private final SueEngine engine = new SueEngine();

    private static QuarterlyEps q(String end, double eps) {
        return new QuarterlyEps(LocalDate.parse(end), BigDecimal.valueOf(eps));
    }

    // 8 contiguous quarters, newest-first. The just-announced quarter (period end ~2026-06,
    // reported 2026-07-28) is NOT in this history; its year-ago is 2025-06-28 (1.00).
    private static List<QuarterlyEps> contiguousHistory() {
        return List.of(
                q("2026-03-28", 1.20), q("2025-12-27", 1.10), q("2025-09-27", 1.05),
                q("2025-06-28", 1.00), q("2025-03-29", 1.00), q("2024-12-28", 0.95),
                q("2024-09-27", 0.90), q("2024-06-28", 0.92));
    }

    @Test
    void computesPositiveSueWithDateBasedAlignment() {
        Sue sue = engine.timeSeriesSue(BigDecimal.valueOf(1.40), LocalDate.parse("2026-07-28"),
                contiguousHistory());
        assertThat(sue.available()).isTrue();
        // year-ago = 1.00; errors ~[0.20,0.15,0.15,0.08], std ~0.0493 -> SUE ~8.1
        assertThat(sue.value()).isCloseTo(8.1, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void unavailableWhenYearAgoQuarterMissing() {
        // remove the 2025-06-28 quarter -> no seasonal partner for the current quarter
        List<QuarterlyEps> gapped = new ArrayList<>(contiguousHistory());
        gapped.removeIf(e -> e.periodEnd().toString().equals("2025-06-28"));
        Sue sue = engine.timeSeriesSue(BigDecimal.valueOf(1.40), LocalDate.parse("2026-07-28"), gapped);
        assertThat(sue.available()).isFalse();
    }

    @Test
    void unavailableWhenHistoryTooShort() {
        List<QuarterlyEps> hist = List.of(q("2026-03-28", 1.20), q("2025-12-27", 1.10));
        assertThat(engine.timeSeriesSue(BigDecimal.valueOf(1.40), LocalDate.parse("2026-07-28"), hist)
                .available()).isFalse();
    }

    @Test
    void seasonalBeatStreakCountsYoYGrowth() {
        assertThat(engine.seasonalBeatStreak(contiguousHistory())).isEqualTo(4);
    }

    @Test
    void rankAssignsDecilesAcrossBatch() {
        List<Double> values = new ArrayList<>();
        for (int i = 1; i <= 30; i++) values.add((double) i);
        assertThat(engine.decile(30.0, values, false)).isEqualTo(10);
        assertThat(engine.decile(1.0, values, false)).isEqualTo(1);
    }

    @Test
    void decileFallsBackToZBandWhenThin() {
        assertThat(engine.decile(2.5, List.of(2.5), true)).isEqualTo(10); // z >= 2 band
    }
}
