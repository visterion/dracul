package de.visterion.dracul;

import de.visterion.dracul.daywalker.DaywalkerAlertRepository;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.sql("DELETE FROM daywalker_alerts WHERE symbol = 'DWA' OR watchlist_item_id IN "
                + "(SELECT id FROM watchlist_items WHERE ticker = 'DWA')").update();
        jdbc.sql("DELETE FROM watchlist_items WHERE ticker = 'DWA'").update();
    }

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

    @org.junit.jupiter.api.Test
    void insertPersistsNotificationSentFlag() {
        var item = watchlist.insert("default", "DWA", "Daywalker Test A",
                50.0, java.util.List.of(50.0), "", null);

        alerts.insert("default", item.id(), "DWA", "INSIDER_SELL",
                "CRITICAL", "Cluster of insider sales.", new java.math.BigDecimal("0.800"),
                "run-dwa-2", true);

        Boolean sent = jdbc.sql(
                "SELECT notification_sent FROM daywalker_alerts WHERE symbol = 'DWA' AND trigger_type = 'INSIDER_SELL'")
                .query(Boolean.class).single();
        assertThat(sent).isTrue();
    }
}
