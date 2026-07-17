package de.visterion.dracul.watchlist;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3: verifies `source` is threaded end-to-end through
 * {@link WatchlistItem} + {@link WatchlistRepository#insert} +
 * {@link WatchlistRepository#findAllByUser}.
 */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class WatchlistRepositorySourceTest {

    @Autowired WatchlistRepository repo;

    @Test
    void insertPersistsSourceAndFindAllByUserReturnsIt() {
        String user = "source-user-" + UUID.randomUUID();

        WatchlistItem created = repo.insert(
                user, "SRC1", "Source One", 42.0, List.of(42.0),
                "HELD", "manual", null, "USD");

        assertThat(created.source()).isEqualTo("manual");

        List<WatchlistItem> all = repo.findAllByUser(user);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).source()).isEqualTo("manual");
    }

    @Test
    void insertWithNullTagWritesTrackingNotEmptyString() {
        String user = "source-user-" + UUID.randomUUID();

        WatchlistItem created = repo.insert(
                user, "SRC2", "Source Two", 10.0, List.of(10.0),
                null, "manual", null, "USD");

        assertThat(created.tag()).isEqualTo("TRACKING");
    }
}
