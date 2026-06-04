package de.visterion.dracul;

import de.visterion.dracul.daywalker.DaywalkerAlertRepository;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class DaywalkerAlertRepositoryIT {

    @Autowired DaywalkerAlertRepository alerts;
    @Autowired WatchlistRepository watchlist;

    @Test
    void insertResolvesWatchlistItemAndCooldownQuery() {
        var item = watchlist.insert("default", "DWA", "Daywalker Test A",
                50.0, List.of(50.0), "", null);

        assertThat(alerts.resolveWatchlistItemId("default", "DWA")).contains(item.id());
        assertThat(alerts.lastAlertAt("default", "DWA", "PRICE_SPIKE")).isEmpty();

        alerts.insert("default", item.id(), "DWA", "PRICE_SPIKE",
                "WARNING", "Sharp intraday move.", new BigDecimal("0.700"), "run-dwa-1");

        assertThat(alerts.lastAlertAt("default", "DWA", "PRICE_SPIKE")).isPresent();
        assertThat(alerts.lastAlertAt("default", "DWA", "VOLUME_SPIKE")).isEmpty();
    }

    @Test
    void resolveReturnsEmptyForUnknownSymbol() {
        assertThat(alerts.resolveWatchlistItemId("default", "NOPE")).isEmpty();
    }
}
