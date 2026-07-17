package de.visterion.dracul.watchlist;

import de.visterion.dracul.ContainerConfig;
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
class WatchlistRepositoryHeldCountTest {

    @Autowired WatchlistRepository repo;

    @Test
    void countsOnlyFullyHeldPositionsForUser() {
        String user = "count-user-" + java.util.UUID.randomUUID();

        var held = repo.insert(user, "HELDCO", "Held Co", 100.0, List.of(100.0), "HELD", "manual", null, null);
        repo.updatePosition(held.id(), 90.0, 10.0, null);

        repo.insert(user, "NOPOS", "No Pos Co", 50.0, List.of(50.0), "HELD", "manual", null, null);

        var watch = repo.insert(user, "WATCHCO", "Watch Co", 20.0, List.of(20.0), "TRACKING", "manual", null, null);
        repo.updatePosition(watch.id(), 18.0, 5.0, null);

        assertThat(repo.countHeldByUser(user)).isEqualTo(1L);
    }

    @Test
    void returnsZeroWhenNoneHeld() {
        String user = "empty-user-" + java.util.UUID.randomUUID();
        repo.insert(user, "WATCHONLY", "Watch Only", 10.0, List.of(10.0), "TRACKING", "manual", null, null);
        assertThat(repo.countHeldByUser(user)).isZero();
    }

    @Test
    void countHeldAll_countsFullyHeldPositionsAcrossUsers() {
        long before = repo.countHeldAll();

        String alice = "all-alice-" + java.util.UUID.randomUUID();
        String bob   = "all-bob-"   + java.util.UUID.randomUUID();

        var aliceHeld = repo.insert(alice, "AAH", "Alice Held", 100.0, List.of(100.0), "HELD", "manual", null, null);
        repo.updatePosition(aliceHeld.id(), 90.0, 10.0, null);

        var bobHeld = repo.insert(bob, "BBH", "Bob Held", 50.0, List.of(50.0), "HELD", "manual", null, null);
        repo.updatePosition(bobHeld.id(), 45.0, 5.0, null);

        // a HELD with no position (no entry/share) must NOT count
        repo.insert(bob, "BNP", "Bob No Pos", 30.0, List.of(30.0), "HELD", "manual", null, null);

        assertThat(repo.countHeldAll()).isEqualTo(before + 2);
    }
}
