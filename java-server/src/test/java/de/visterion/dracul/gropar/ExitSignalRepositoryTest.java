package de.visterion.dracul.gropar;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class ExitSignalRepositoryTest {

    @Autowired ExitSignalRepository repo;
    @Autowired WatchlistRepository watchlistRepo;

    @Test
    void insertAndReadBack() {
        var s = new ExitSignal(java.util.UUID.randomUUID().toString(), null, "ACME", "SELL",
                List.of("CHANDELIER_STOP", "DEATH_CROSS"), -12.5, "INVALIDATED",
                "Broke chandelier stop and thesis risks materialised.", 0.82,
                "run-x", java.time.Instant.now().toString());
        repo.insert(s, "default");
        var latest = repo.findLatestByUser("default", 10);
        assertThat(latest).anySatisfy(r -> {
            assertThat(r.symbol()).isEqualTo("ACME");
            assertThat(r.action()).isEqualTo("SELL");
            assertThat(r.firedRules()).contains("DEATH_CROSS");
            assertThat(r.gainLossPct()).isEqualTo(-12.5);
            assertThat(r.thesisStatus()).isEqualTo("INVALIDATED");
        });
    }

    @Test
    void nullableNumericsRoundTripAsNull() {
        var s = new ExitSignal(java.util.UUID.randomUUID().toString(), null, "NULLCO", "HOLD",
                List.of(), null, null, "hold", null, "run-y", java.time.Instant.now().toString());
        repo.insert(s, "default");
        var latest = repo.findLatestByUser("default", 50);
        var found = latest.stream().filter(r -> r.symbol().equals("NULLCO")).findFirst();
        assertThat(found).isPresent();
        assertThat(found.get().gainLossPct()).isNull();
        assertThat(found.get().confidence()).isNull();
    }

    @Test
    void duplicateRunAndItemIsNotInsertedTwice() {
        var item = watchlistRepo.insert("default", "ACME", "Acme Corp",
                50.0, List.of(50.0), "HELD", "manual", null, null);
        String itemId = item.id();
        var s1 = new ExitSignal(java.util.UUID.randomUUID().toString(), itemId, "ACME", "SELL",
                List.of(), -3.0, "WEAKENING", "r", 0.7, "run-dup", java.time.Instant.now().toString());
        var s2 = new ExitSignal(java.util.UUID.randomUUID().toString(), itemId, "ACME", "SELL",
                List.of(), -3.0, "WEAKENING", "r", 0.7, "run-dup", java.time.Instant.now().toString());
        assertThat(repo.insert(s1, "default")).isTrue();
        assertThat(repo.insert(s2, "default")).isFalse();
    }
}
