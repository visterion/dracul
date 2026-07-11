package de.visterion.dracul.prey;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class PreyRepositoryIT {

    @Autowired PreyRepository repo;

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
}
