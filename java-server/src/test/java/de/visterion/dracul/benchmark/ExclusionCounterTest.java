package de.visterion.dracul.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static de.visterion.dracul.benchmark.BenchmarkFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;

/** Final review I3: excluded events per Strigoi and group. */
class ExclusionCounterTest {

    private static final String A = "strigoi-syna";

    @Test
    void countsNoEntryGivenUpAndUnknownCurrencyPerGroup() {
        Map<String, AgentExclusions> x = ExclusionCounter.count(
                List.of(new ExclusionCounter.NoEntryCount(EventKind.SIGNAL, A, null, 2),
                        new ExclusionCounter.NoEntryCount(EventKind.CANDIDATE, A, true, 1),
                        new ExclusionCounter.NoEntryCount(EventKind.CANDIDATE, A, false, 3)),
                List.of(new ExclusionCounter.UnmeasuredPair(EventKind.SIGNAL, A, "SYNX.ZZ", null),
                        new ExclusionCounter.UnmeasuredPair(EventKind.SIGNAL, A, "SYNG", null),
                        new ExclusionCounter.UnmeasuredPair(EventKind.CANDIDATE, A, "SYNG", false),
                        new ExclusionCounter.UnmeasuredPair(EventKind.CANDIDATE, A, "SYNY.ZZ", true),
                        // Two nights only — still being retried, not excluded yet.
                        new ExclusionCounter.UnmeasuredPair(EventKind.CANDIDATE, A, "SYNH", false),
                        // Known currency, no no-data row — merely pending, not excluded.
                        new ExclusionCounter.UnmeasuredPair(EventKind.CANDIDATE, A, "SYNP", false)),
                Map.of("SYNG", new BenchmarkOutcomeRepository.NoDataState(3, d(2026, 6, 1)),
                        "SYNH", new BenchmarkOutcomeRepository.NoDataState(2, d(2026, 6, 1))));

        AgentExclusions a = x.get(A);
        assertThat(a.signal()).isEqualTo(ExcludedCounts.of(2, 1, 1));
        assertThat(a.picked()).isEqualTo(ExcludedCounts.of(1, 0, 1));
        assertThat(a.rejected()).isEqualTo(ExcludedCounts.of(3, 1, 0));
        assertThat(a.signal().total()).isEqualTo(4);
        assertThat(a.total()).isEqualTo(4 + 2 + 4);
    }

    @Test
    void nothingExcludedIsAnEmptyMap() {
        assertThat(ExclusionCounter.count(List.of(), List.of(), Map.of())).isEmpty();
    }
}
