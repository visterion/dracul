package de.visterion.dracul.benchmark;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BenchmarkSummaryServiceTest {

    private final BenchmarkOutcomeRepository outcomes = mock(BenchmarkOutcomeRepository.class);
    private final ScreenCandidateRepository candidates = mock(ScreenCandidateRepository.class);
    private final DepotBenchmarkService depot = mock(DepotBenchmarkService.class);

    private static StatRow row(EventKind kind, String agent, Boolean picked, int h, String excess) {
        return new StatRow(kind, agent, picked, h, LocalDate.of(2026, 3, 2), new BigDecimal(excess));
    }

    @Test
    void groupsByAgentKindPickedAndHorizonAndUnionsCoverageAgents() {
        when(outcomes.findForStats()).thenReturn(List.of(
                row(EventKind.SIGNAL, "strigoi-syna", null, 20, "0.02"),
                row(EventKind.SIGNAL, "strigoi-syna", null, 20, "0.04"),
                row(EventKind.SIGNAL, "strigoi-syna", null, 60, "-0.01"),
                row(EventKind.CANDIDATE, "strigoi-syna", true, 60, "0.05"),
                row(EventKind.CANDIDATE, "strigoi-syna", false, 60, "0.01")));
        when(candidates.coverageStart()).thenReturn(Map.of("strigoi-synb", LocalDate.of(2026, 7, 14)));
        DepotBenchmark block = new DepotBenchmark("depot-1", null, null, null, null, null, List.of());
        when(depot.block("owner@example.com")).thenReturn(block);

        BenchmarkSummary s = new BenchmarkSummaryService(outcomes, candidates, depot, "2027-04-13")
                .summary("owner@example.com");

        assertThat(s.benchmark()).isEqualTo("EUNL.DE");
        assertThat(s.decisionDate()).isEqualTo("2027-04-13");
        assertThat(s.minEvents()).isEqualTo(30);
        assertThat(s.minWeeks()).isEqualTo(24);
        assertThat(s.depot()).isSameAs(block);
        assertThat(s.agents()).extracting(AgentBenchmark::agent).containsExactly("strigoi-syna", "strigoi-synb");

        AgentBenchmark a = s.agents().getFirst();
        assertThat(a.signal20().n()).isEqualTo(2);
        assertThat(a.signal20().mean()).isEqualByComparingTo("0.03");
        assertThat(a.signal60().n()).isEqualTo(1);
        assertThat(a.paired20()).isNull();
        assertThat(a.paired60().pickedN()).isEqualTo(1);
        assertThat(a.paired60().rejectedN()).isEqualTo(1);
        assertThat(a.paired60().difference()).isEqualByComparingTo("0.04");
        assertThat(a.status()).isEqualTo(BenchmarkStatus.INSUFFICIENT);
        assertThat(a.coverageStart()).isNull();

        AgentBenchmark b = s.agents().get(1);
        assertThat(b.coverageStart()).isEqualTo("2026-07-14");
        assertThat(b.signal60()).isEqualTo(SignalStats.empty());
        assertThat(b.paired60()).isNull();
        assertThat(b.excluded()).isEqualTo(AgentExclusions.NONE);
    }

    @Test
    void anInvalidDecisionDateFailsFast() {
        assertThatThrownBy(() -> new BenchmarkSummaryService(outcomes, candidates, depot, "April"))
                .isInstanceOf(DateTimeParseException.class);
    }

    /** Final review M4 / spec §2.5: insider has no control group — paired is null even when a few
     *  rejected clusters were measured. */
    @Test
    void insiderNeverGetsAPairedComparison() {
        when(outcomes.findForStats()).thenReturn(List.of(
                row(EventKind.SIGNAL, "strigoi-insider", null, 60, "0.01"),
                row(EventKind.CANDIDATE, "strigoi-insider", true, 20, "0.05"),
                row(EventKind.CANDIDATE, "strigoi-insider", false, 20, "0.01"),
                row(EventKind.CANDIDATE, "strigoi-insider", true, 60, "0.05"),
                row(EventKind.CANDIDATE, "strigoi-insider", false, 60, "0.01")));
        when(candidates.coverageStart()).thenReturn(Map.of());

        AgentBenchmark a = new BenchmarkSummaryService(outcomes, candidates, depot, "2027-04-13")
                .summary("owner@example.com").agents().getFirst();

        assertThat(a.agent()).isEqualTo("strigoi-insider");
        assertThat(a.signal60().n()).isEqualTo(1);
        assertThat(a.paired20()).isNull();
        assertThat(a.paired60()).isNull();
    }

    /** Final review I3: exclusions reach the row, and an agent whose every event is excluded
     *  still gets a row. */
    @Test
    void exclusionsAreAttachedAndAddExclusionOnlyAgents() {
        when(outcomes.findForStats()).thenReturn(List.of(row(EventKind.SIGNAL, "strigoi-syna", null, 20, "0.01")));
        when(candidates.coverageStart()).thenReturn(Map.of());
        when(outcomes.noEntryCounts()).thenReturn(List.of(
                new ExclusionCounter.NoEntryCount(EventKind.SIGNAL, "strigoi-synz", null, 2)));
        when(outcomes.unmeasuredPairs()).thenReturn(List.of(
                new ExclusionCounter.UnmeasuredPair(EventKind.CANDIDATE, "strigoi-syna", "SYNX.ZZ", false)));

        BenchmarkSummary s = new BenchmarkSummaryService(outcomes, candidates, depot, "2027-04-13")
                .summary("owner@example.com");

        assertThat(s.agents()).extracting(AgentBenchmark::agent).containsExactly("strigoi-syna", "strigoi-synz");
        assertThat(s.agents().get(0).excluded().rejected()).isEqualTo(ExcludedCounts.of(0, 0, 1));
        assertThat(s.agents().get(0).excluded().total()).isEqualTo(1);
        assertThat(s.agents().get(1).excluded().signal().noEntry()).isEqualTo(2);
        assertThat(s.agents().get(1).signal60()).isEqualTo(SignalStats.empty());
    }
}
