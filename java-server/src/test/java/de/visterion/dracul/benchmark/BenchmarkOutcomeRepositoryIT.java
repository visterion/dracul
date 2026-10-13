package de.visterion.dracul.benchmark;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class BenchmarkOutcomeRepositoryIT {

    @Autowired BenchmarkOutcomeRepository repo;
    @Autowired ScreenCandidateRepository candidates;
    @Autowired JdbcClient jdbc;

    private static String uniq(String prefix) {
        return prefix + Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
    }

    private static BenchmarkOutcome row(String agent, String symbol, int horizon) {
        return new BenchmarkOutcome(EventKind.CANDIDATE, agent, symbol, LocalDate.of(2026, 3, 2),
                LocalDate.of(2026, 3, 1), Instant.parse("2026-03-01T22:00:00Z"), true, horizon,
                LocalDate.of(2026, 3, 2), new BigDecimal("10"), LocalDate.of(2026, 3, 30), new BigDecimal("11"),
                "USD", new BigDecimal("0.9"), new BigDecimal("0.9"), new BigDecimal("0.1"),
                new BigDecimal("0.02"), new BigDecimal("0.08"), ExitKind.NORMAL);
    }

    @Test
    void insertIsOnceOnlyAndShowsUpAsACompletedEventKey() {
        String agent = uniq("strigoi-syn");
        assertThat(repo.insert(row(agent, "SYNA", 20))).isTrue();
        assertThat(repo.insert(row(agent, "SYNA", 20))).isFalse();
        assertThat(repo.completedEventKeys()).contains(
                BenchmarkOutcomeRepository.eventKey(EventKind.CANDIDATE, agent, "SYNA", LocalDate.of(2026, 3, 2), 20),
                // Final review I1b: the unique-key tuple (entry_ref_date 2026-03-01) is a done key too.
                BenchmarkOutcomeRepository.refKey(EventKind.CANDIDATE, agent, "SYNA", LocalDate.of(2026, 3, 1), 20));
    }

    @Test
    void noEntryRowWithNullsIsStored() {
        String agent = uniq("strigoi-syn");
        MeasureEvent e = new MeasureEvent(EventKind.SIGNAL, agent, "SYNB", LocalDate.of(2026, 3, 10),
                Instant.parse("2026-03-10T22:00:00Z"), null, LocalDate.of(2026, 3, 10), null);
        assertThat(repo.insert(BenchmarkOutcome.noEntry(e, 60, "USD"))).isTrue();
        String kind = jdbc.sql("SELECT exit_kind FROM benchmark_outcome WHERE agent = :a")
                .param("a", agent).query(String.class).single();
        assertThat(kind).isEqualTo("NO_ENTRY");
    }

    @Test
    void noDataCountsAtMostOncePerNightAndClears() {
        String sym = uniq("SYNN");
        assertThat(repo.noDataNights(sym)).isZero();
        repo.recordNoData(sym, LocalDate.of(2026, 6, 30));
        repo.recordNoData(sym, LocalDate.of(2026, 6, 30));
        assertThat(repo.noDataNights(sym)).isEqualTo(1);
        repo.recordNoData(sym, LocalDate.of(2026, 7, 1));
        assertThat(repo.noDataNights(sym)).isEqualTo(2);
        assertThat(repo.noDataStates()).containsEntry(sym,
                new BenchmarkOutcomeRepository.NoDataState(2, LocalDate.of(2026, 7, 1)));
        repo.clearNoData(sym);
        assertThat(repo.noDataNights(sym)).isZero();
        assertThat(repo.noDataStates()).doesNotContainKey(sym);
    }

    @Test
    void preyIsReadUpperCasedAndCandidatesCarryTheOffer() {
        String sym = uniq("SYNP");
        jdbc.sql("""
                INSERT INTO prey (id, symbol, company_name, anomaly_type, confidence, thesis, horizon,
                                  discovered_by, discovered_at)
                VALUES (:id, :sym, 'Synthetic', 'TEST', 0.5, 'synthetic thesis', '3m', 'strigoi-merger', :at)""")
                .param("id", UUID.randomUUID()).param("sym", " " + sym.toLowerCase(Locale.ROOT) + " ")
                .param("at", Timestamp.from(Instant.parse("2026-03-02T22:00:00Z"))).update();
        assertThat(repo.findPrey()).contains(new PreyRow("strigoi-merger", sym, Instant.parse("2026-03-02T22:00:00Z")));

        String agent = uniq("strigoi-syn");
        candidates.insert(agent, Instant.parse("2026-03-02T05:00:00Z"), new ScreenItem(sym, null,
                JsonNodeFactory.instance.objectNode().put("offerPrice", new BigDecimal("52.5"))), "LIVE");
        CandidateRow c = repo.findCandidates().stream().filter(r -> r.agent().equals(agent)).findFirst().orElseThrow();
        assertThat(c.symbol()).isEqualTo(sym);
        assertThat(c.asOfDate()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(c.offerPrice()).isEqualByComparingTo("52.5");
        assertThat(c.offerCurrency()).isNull();
    }

    /** Final review I3: NO_ENTRY events are counted per (kind, agent, picked), distinct per event
     *  (both horizons of one event count once); unmeasured pairs come from prey and candidates. */
    @Test
    void exclusionQueriesCountNoEntryEventsAndUnmeasuredPairs() {
        String agent = uniq("strigoi-syn");
        MeasureEvent e = new MeasureEvent(EventKind.SIGNAL, agent, "SYNB", LocalDate.of(2026, 3, 10),
                Instant.parse("2026-03-10T22:00:00Z"), null, LocalDate.of(2026, 3, 10), null);
        repo.insert(BenchmarkOutcome.noEntry(e, 20, "USD"));
        repo.insert(BenchmarkOutcome.noEntry(e, 60, "USD"));
        assertThat(repo.noEntryCounts()).contains(new ExclusionCounter.NoEntryCount(EventKind.SIGNAL, agent, null, 1));

        String measured = uniq("SYNM");
        String unmeasured = uniq("SYNU");
        for (String sym : List.of(measured, unmeasured)) {
            jdbc.sql("""
                    INSERT INTO prey (id, symbol, company_name, anomaly_type, confidence, thesis, horizon,
                                      discovered_by, discovered_at)
                    VALUES (:id, :sym, 'Synthetic', 'TEST', 0.5, 'synthetic thesis', '3m', :agent, :at)""")
                    .param("id", UUID.randomUUID()).param("sym", sym).param("agent", agent)
                    .param("at", Timestamp.from(Instant.parse("2026-03-02T22:00:00Z"))).update();
        }
        repo.insert(new BenchmarkOutcome(EventKind.SIGNAL, agent, measured, LocalDate.of(2026, 3, 2),
                LocalDate.of(2026, 3, 2), Instant.parse("2026-03-02T22:00:00Z"), null, 20,
                LocalDate.of(2026, 3, 3), new BigDecimal("10"), LocalDate.of(2026, 3, 31), new BigDecimal("11"),
                "USD", new BigDecimal("0.9"), new BigDecimal("0.9"), new BigDecimal("0.1"),
                new BigDecimal("0.02"), new BigDecimal("0.08"), ExitKind.NORMAL));
        String rejected = uniq("SYNR");
        candidates.insert(agent, Instant.parse("2026-03-02T05:00:00Z"), new ScreenItem(unmeasured, null, null), "LIVE");
        candidates.insert(agent, Instant.parse("2026-03-02T05:00:00Z"), new ScreenItem(rejected, null, null), "LIVE");

        List<ExclusionCounter.UnmeasuredPair> pairs = repo.unmeasuredPairs().stream()
                .filter(p -> p.agent().equals(agent)).toList();
        assertThat(pairs).containsExactlyInAnyOrder(
                new ExclusionCounter.UnmeasuredPair(EventKind.SIGNAL, agent, unmeasured, null),
                new ExclusionCounter.UnmeasuredPair(EventKind.CANDIDATE, agent, unmeasured, true),
                new ExclusionCounter.UnmeasuredPair(EventKind.CANDIDATE, agent, rejected, false));
    }
}
