package de.visterion.dracul.prey;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class PreyRepositoryIT {

    @Autowired PreyRepository repo;
    @Autowired JdbcClient jdbc;

    private Prey preyFixture(String symbol, String anomalyType, String discoveredBy, String discoveredAt) {
        return new Prey(
                UUID.randomUUID().toString(), symbol, symbol + " Corp", anomalyType,
                0.7, "thesis", List.of("signal"), List.of("risk"),
                List.of(), "6m", discoveredBy, discoveredAt);
    }

    @Test
    void insertAllSkipsDuplicatesAndReturnsInsertedSubset() {
        Prey p = preyFixture("ACMEDUP", "SPIN_OFF", "strigoi-spin", "2026-07-10T08:00:00Z");
        List<Prey> first = repo.insertAll(List.of(p));
        assertThat(first).hasSize(1);

        Prey retry = preyFixture("ACMEDUP", "SPIN_OFF", "strigoi-spin", "2026-07-10T09:30:00Z"); // same day, new UUID
        List<Prey> second = repo.insertAll(List.of(retry));
        assertThat(second).isEmpty(); // conflict -> not inserted

        assertThat(repo.findAllByUser("default"))
                .filteredOn(x -> x.symbol().equals("ACMEDUP"))
                .hasSize(1);
    }

    @Test
    void killCriteriaRoundTrips() {
        String symbol = "KCIT" + System.nanoTime();
        var p = new Prey(
                UUID.randomUUID().toString(), symbol, symbol + " Corp", "SPINOFF",
                0.7, "thesis", List.of("signal"), List.of("risk"),
                List.of("Close below 42.50", "No approval by 2026-10-15"),
                "6m", "strigoi-spin", "2026-07-09T10:00:00Z");

        repo.insertAll(List.of(p));

        assertThat(repo.findAllByUser("default"))
                .filteredOn(x -> x.symbol().equals(symbol))
                .singleElement()
                .satisfies(x -> assertThat(x.killCriteria())
                        .containsExactly("Close below 42.50", "No approval by 2026-10-15"));
    }

    @Test
    void findByIds_returnsMatchingPreyWithKillCriteriaRoundTrip() {
        Prey p1raw = preyFixture("FBID1", "SPINOFF", "strigoi-spin", "2026-07-09T10:00:00Z");
        Prey p1 = new Prey(p1raw.id(), p1raw.symbol(), p1raw.companyName(), p1raw.anomalyType(), p1raw.confidence(),
                p1raw.thesis(), p1raw.signals(), p1raw.risks(), List.of("Close below 42.50"),
                p1raw.horizon(), p1raw.discoveredBy(), p1raw.discoveredAt());
        Prey p2 = preyFixture("FBID2", "SPINOFF", "strigoi-spin", "2026-07-09T10:00:00Z");

        List<Prey> inserted = repo.insertAll(List.of(p1, p2));
        assertThat(inserted).hasSize(2);

        List<Prey> found = repo.findByIds(List.of(p1.id(), p2.id()));
        assertThat(found).hasSize(2);
        assertThat(found).filteredOn(x -> x.id().equals(p1.id()))
                .singleElement()
                .satisfies(x -> assertThat(x.killCriteria()).containsExactly("Close below 42.50"));

        assertThat(repo.findByIds(List.of())).isEmpty();
        assertThat(repo.findByIds(null)).isEmpty();
    }

    @Test
    void findElapsedUnreviewed_excludesReviewedAndOtherUsers_ordersOldestFirst() {
        String symOld = "OUT" + System.nanoTime();
        String symNew = "OUT" + System.nanoTime() + 1;
        Prey older = preyFixture(symOld, "SPINOFF", "strigoi-spin", "2026-01-01T00:00:00Z");
        Prey newer = preyFixture(symNew, "SPINOFF", "strigoi-spin", "2026-02-01T00:00:00Z");
        List<Prey> inserted = repo.insertAll(List.of(newer, older));
        assertThat(inserted).hasSize(2);

        List<Prey> unreviewed = repo.findElapsedUnreviewed("default", null);
        assertThat(unreviewed).filteredOn(p -> p.symbol().equals(symOld) || p.symbol().equals(symNew))
                .extracting(Prey::symbol)
                .containsSubsequence(symOld, symNew); // oldest discoveredAt first

        String olderId = unreviewed.stream().filter(p -> p.symbol().equals(symOld)).findFirst().orElseThrow().id();
        repo.markOutcomeReviewed(List.of(olderId));

        List<Prey> afterReview = repo.findElapsedUnreviewed("default", null);
        assertThat(afterReview).extracting(Prey::symbol).doesNotContain(symOld);
        assertThat(afterReview).extracting(Prey::symbol).contains(symNew);
    }

    @Test
    void findElapsedUnreviewed_lookbackDaysBoundsToRecentDiscoveries() {
        String symRecent = "LB" + System.nanoTime();
        Prey recent = preyFixture(symRecent, "SPINOFF", "strigoi-spin",
                java.time.Instant.now().toString());
        repo.insertAll(List.of(recent));

        List<Prey> withinLookback = repo.findElapsedUnreviewed("default", 5);
        assertThat(withinLookback).extracting(Prey::symbol).contains(symRecent);

        List<Prey> outsideLookback = repo.findElapsedUnreviewed("default", 0);
        assertThat(outsideLookback).extracting(Prey::symbol).doesNotContain(symRecent);
    }

    @Test
    void markOutcomeReviewed_emptyCollection_isNoop() {
        repo.markOutcomeReviewed(List.of());
        repo.markOutcomeReviewed(null);
        // no exception is the assertion
    }

    @Test
    void insertAllPersistsRunId() {
        Prey p = preyFixture("RUNID" + System.nanoTime(), "SPINOFF", "strigoi-spin", "2026-07-09T10:00:00Z");
        repo.insertAll(List.of(p), "run-xyz");
        String runId = jdbc.sql("SELECT run_id FROM prey WHERE id = ?::uuid")
                .param(p.id()).query(String.class).single();
        assertThat(runId).isEqualTo("run-xyz");
    }

    @Test
    void insertAllLegacySignature_leavesRunIdNull() {
        Prey p = preyFixture("RUNIDNULL" + System.nanoTime(), "SPINOFF", "strigoi-spin", "2026-07-09T10:00:00Z");
        repo.insertAll(List.of(p));
        String runId = jdbc.sql("SELECT run_id FROM prey WHERE id = ?::uuid")
                .param(p.id()).query(String.class).optional().orElse(null);
        assertThat(runId).isNull();
    }

    @Test
    void runExistsForUser_trueWhenOwnedByGivenUser() {
        String runId = "run-owned-" + System.nanoTime();
        Prey p = preyFixture("OWN" + System.nanoTime(), "SPINOFF", "strigoi-spin", "2026-07-09T10:00:00Z");
        repo.insertAll(List.of(p), runId); // inserted rows are always stamped user_id="default"

        assertThat(repo.runExistsForUser(runId, "default")).isTrue();
    }

    @Test
    void runExistsForUser_falseForOtherUserOrUnknownRun() {
        String runId = "run-other-" + System.nanoTime();
        Prey p = preyFixture("OTH" + System.nanoTime(), "SPINOFF", "strigoi-spin", "2026-07-09T10:00:00Z");
        repo.insertAll(List.of(p), runId);

        assertThat(repo.runExistsForUser(runId, "someone-else")).isFalse();
        assertThat(repo.runExistsForUser("run-does-not-exist", "default")).isFalse();
    }
}
