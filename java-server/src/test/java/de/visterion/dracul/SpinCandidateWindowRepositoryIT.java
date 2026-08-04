package de.visterion.dracul;

import de.visterion.dracul.strigoi.spin.SpinCandidate;
import de.visterion.dracul.strigoi.spin.SpinCandidateRepository;
import de.visterion.dracul.strigoi.spin.SpinCandidateRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D11, SQL side: the RESPOND payload must be filterable by the requested window.
 *
 * <p>Which date. {@code lookback_days} is already an EDGAR FILING-DATE window on the ingest side
 * ({@code searchSpinoffs(to.minusDays(lookback), to)}), so the filing date is the primary clock —
 * one parameter must not mean two different things at the two ends of the same hunt. But a
 * spin-off's tradeable event is the DISTRIBUTION, which lands weeks or months after the 10-12B;
 * excluding a freshly-distributed spin-co because its registration is old would delete exactly the
 * candidates this hunter exists for. So a row is in-window when EITHER date falls inside it.
 *
 * <p>{@code discovered_at} is deliberately NOT the filter: it records when Dracul's cron first saw
 * the row, not when anything happened in the market — a backfill or a re-ingest would make every
 * row look brand new.
 */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class SpinCandidateWindowRepositoryIT {

    @Autowired SpinCandidateRepository repo;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM spin_candidate").update();
    }

    private static SpinCandidate candidate(String cik, String symbol, String name, String filed) {
        return new SpinCandidate(symbol, name, "10-12B", filed, "https://sec/" + name, cik);
    }

    private static List<String> names(List<SpinCandidateRow> rows) {
        return rows.stream().map(SpinCandidateRow::companyName).toList();
    }

    @Test
    void filingDateInsideTheWindowIsReturnedAndAnOlderOneIsNot() {
        LocalDate today = LocalDate.now();
        repo.upsertRegistered(candidate("0000009101", "FRESH", "Window Fresh Co",
                today.minusDays(5).toString()));
        repo.upsertRegistered(candidate("0000009102", "STALE", "Window Stale Co",
                today.minusDays(70).toString()));

        List<SpinCandidateRow> rows = repo.findActiveUnpromotedInWindow(today.minusDays(14), 50);

        assertThat(names(rows)).containsExactly("Window Fresh Co");
    }

    @Test
    void anOldRegistrationWithARecentDistributionStaysInTheWindow() {
        LocalDate today = LocalDate.now();
        repo.upsertRegistered(candidate("0000009103", "DIST", "Window Distributed Co",
                today.minusDays(200).toString()));
        jdbc.sql("UPDATE spin_candidate SET distribution_date = :d WHERE cik = '0000009103'")
                .param("d", today.minusDays(3)).update();

        List<SpinCandidateRow> rows = repo.findActiveUnpromotedInWindow(today.minusDays(14), 50);

        assertThat(names(rows)).containsExactly("Window Distributed Co");
    }

    @Test
    void anUndatedRowIsNeverSilentlyDroppedByTheWindow() {
        LocalDate today = LocalDate.now();
        repo.upsertRegistered(candidate("0000009104", "NODATE", "Window Undated Co", null));

        List<SpinCandidateRow> rows = repo.findActiveUnpromotedInWindow(today.minusDays(14), 50);

        assertThat(names(rows)).containsExactly("Window Undated Co");
    }

    @Test
    void statusAndPromotionFiltersStillApply() {
        LocalDate today = LocalDate.now();
        repo.upsertRegistered(candidate("0000009105", "PROM", "Window Promoted Co",
                today.minusDays(2).toString()));
        jdbc.sql("UPDATE spin_candidate SET promoted_at = now() WHERE cik = '0000009105'").update();
        repo.upsertRegistered(candidate("0000009106", "ABND", "Window Abandoned Co",
                today.minusDays(2).toString()));
        jdbc.sql("UPDATE spin_candidate SET status = 'ABANDONED' WHERE cik = '0000009106'").update();

        List<SpinCandidateRow> rows = repo.findActiveUnpromotedInWindow(today.minusDays(14), 50);

        assertThat(rows).isEmpty();
    }

    @Test
    void limitIsHonoured() {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 4; i++) {
            repo.upsertRegistered(candidate("000000920" + i, "L" + i, "Window Limit Co " + i,
                    today.minusDays(1).toString()));
        }
        assertThat(repo.findActiveUnpromotedInWindow(today.minusDays(14), 2)).hasSize(2);
    }

    /**
     * D11's {@code ORDER BY discovered_at DESC} has no tiebreaker, and every row of one ingest
     * pass is inserted inside the same few milliseconds — the observed spread was 25 ms, well
     * inside {@code timestamptz} resolution for rows that share a batch. Ties therefore came back
     * in whatever order the scan happened to produce, so the {@code LIMIT} cut a different,
     * unpredictable subset from run to run and the same candidate could appear and vanish without
     * anything having changed. A deterministic secondary key is what makes the cut reproducible.
     */
    @Test
    void rowsSharingADiscoveryTimestampAreOrderedDeterministically() {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 6; i++) {
            repo.upsertRegistered(candidate("000000930" + i, "T" + i, "Window Tie Co " + i,
                    today.minusDays(1).toString()));
        }
        // Collapse every discovered_at onto one instant: the exact condition a same-batch ingest
        // produces, made exact so the test cannot pass by accident of clock resolution.
        jdbc.sql("UPDATE spin_candidate SET discovered_at = timestamptz '2026-08-04 05:00:00+00' "
                + "WHERE cik LIKE '000000930%'").update();

        // Asserting only "two identical queries agree" is NOT enough: on a six-row heap Postgres
        // returns the insertion order both times, so such a test passes with or without the
        // tiebreaker and proves nothing. The order is therefore pinned to what the tiebreaker
        // SPECIFIES — cik DESC, the exact REVERSE of the insertion order the scan would otherwise
        // hand back — so the assertion can only hold if the ORDER BY is really total.
        List<String> expected = List.of(
                "Window Tie Co 5", "Window Tie Co 4", "Window Tie Co 3",
                "Window Tie Co 2", "Window Tie Co 1", "Window Tie Co 0");

        assertThat(names(repo.findActiveUnpromotedInWindow(today.minusDays(14), 50)))
                .as("tied discovered_at must be broken by cik DESC, not by the scan order")
                .isEqualTo(expected);
        // And the LIMIT must cut that same specified order, not a fresh arbitrary one.
        assertThat(names(repo.findActiveUnpromotedInWindow(today.minusDays(14), 3)))
                .isEqualTo(expected.subList(0, 3));
    }
}
