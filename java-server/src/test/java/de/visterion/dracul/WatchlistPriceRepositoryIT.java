package de.visterion.dracul;

import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class WatchlistPriceRepositoryIT {

    @Autowired WatchlistRepository repo;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.sql("DELETE FROM watchlist_items WHERE ticker IN ('TSTX', 'TSTY')").update();
    }

    @Test
    void distinctTickersAndUpdatePrice() {
        // Two rows for TSTX under different users (unique constraint is per user+ticker)
        repo.insert("user1", "TSTX", "Test X", 100.0, java.util.List.of(), null, "manual", null, null);
        repo.insert("user2", "TSTX", "Test X dup", 100.0, java.util.List.of(), null, "manual", null, null);
        repo.insert("default", "TSTY", "Test Y", 50.0, java.util.List.of(), null, "manual", null, null);

        org.assertj.core.api.Assertions.assertThat(repo.distinctTickers()).contains("TSTX", "TSTY");

        int rows = repo.updatePriceByTicker("TSTX", 222.5, -1.25);
        org.assertj.core.api.Assertions.assertThat(rows).isEqualTo(2); // both TSTX rows updated

        var updated = repo.findAll().stream().filter(i -> i.ticker().equals("TSTX")).toList();
        org.assertj.core.api.Assertions.assertThat(updated).allSatisfy(i -> {
            org.assertj.core.api.Assertions.assertThat(i.currentPrice()).isEqualTo(222.5);
            org.assertj.core.api.Assertions.assertThat(i.dayChangePercent()).isEqualTo(-1.25);
        });
    }
}
