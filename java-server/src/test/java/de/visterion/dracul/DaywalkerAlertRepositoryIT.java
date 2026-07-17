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
                50.0, List.of(50.0), "", null, null);

        assertThat(alerts.findOwnersBySymbol("DWA"))
                .containsExactly(new DaywalkerAlertRepository.OwnerItem("default", item.id(), false));
        assertThat(alerts.lastAlertAt("default", "DWA", "PRICE_SPIKE")).isEmpty();

        alerts.insert("default", item.id(), "DWA", "PRICE_SPIKE",
                "WARNING", "Sharp intraday move.", new BigDecimal("0.700"), "run-dwa-1");

        assertThat(alerts.lastAlertAt("default", "DWA", "PRICE_SPIKE")).isPresent();
        assertThat(alerts.lastAlertAt("default", "DWA", "VOLUME_SPIKE")).isEmpty();
    }

    @Test
    void findOwnersReturnsEmptyForUnknownSymbol() {
        assertThat(alerts.findOwnersBySymbol("NOPE")).isEmpty();
    }

    @Test
    void findOwnersBySymbolReturnsAllOwnersAcrossUsers() {
        var a = watchlist.insert("u1@x.com", "DWA", "Daywalker Test A",
                50.0, List.of(50.0), "", null, null);
        var b = watchlist.insert("u2@x.com", "DWA", "Daywalker Test A",
                50.0, List.of(50.0), "", null, null);
        assertThat(alerts.findOwnersBySymbol("DWA"))
                .containsExactlyInAnyOrder(
                        new DaywalkerAlertRepository.OwnerItem("u1@x.com", a.id(), false),
                        new DaywalkerAlertRepository.OwnerItem("u2@x.com", b.id(), false));
    }

    @Test
    void findOwnersBySymbolReportsHeldFlag() {
        var a = watchlist.insert("u1@x.com", "DWA", "Daywalker Test A",
                50.0, List.of(50.0), "HELD", null, null);
        var b = watchlist.insert("u2@x.com", "DWA", "Daywalker Test A",
                50.0, List.of(50.0), "calm", null, null);

        var owners = alerts.findOwnersBySymbol("DWA");

        assertThat(owners).hasSize(2);
        assertThat(owners).anyMatch(o -> o.userId().equals("u1@x.com") && o.held());
        assertThat(owners).anyMatch(o -> o.userId().equals("u2@x.com") && !o.held());
    }

    @org.junit.jupiter.api.Test
    void insertPersistsNotificationSentFlag() {
        var item = watchlist.insert("default", "DWA", "Daywalker Test A",
                50.0, java.util.List.of(50.0), "", null, null);

        alerts.insert("default", item.id(), "DWA", "INSIDER_SELL",
                "CRITICAL", "Cluster of insider sales.", new java.math.BigDecimal("0.800"),
                "run-dwa-2", true);

        Boolean sent = jdbc.sql(
                "SELECT notification_sent FROM daywalker_alerts WHERE symbol = 'DWA' AND trigger_type = 'INSIDER_SELL'")
                .query(Boolean.class).single();
        assertThat(sent).isTrue();
    }

    @Test
    void sameUtcDayLookupFindsAndUpdatesInPlace() {
        var item = watchlist.insert("default", "DWA", "Daywalker Test A",
                50.0, List.of(50.0), "", null, null);
        alerts.insert("default", item.id(), "DWA", "PRICE_SPIKE",
                "INFO", "first thesis", new BigDecimal("0.500"), "run-1");

        var found = alerts.findSameUtcDay("default", "DWA", "PRICE_SPIKE", java.time.Instant.now());
        assertThat(found).isPresent();
        assertThat(found.get().severity()).isEqualTo("INFO");
        assertThat(alerts.findSameUtcDay("default", "DWA", "VOLUME_SPIKE", java.time.Instant.now())).isEmpty();

        alerts.updateSameDayAlert(found.get().id(), "PRICE_SPIKE", "CRITICAL",
                "second thesis", new BigDecimal("0.900"), "run-2", true);

        var after = alerts.findSameUtcDay("default", "DWA", "PRICE_SPIKE", java.time.Instant.now());
        assertThat(after).isPresent();
        assertThat(after.get().severity()).isEqualTo("CRITICAL");
        Long count = jdbc.sql("SELECT COUNT(*) FROM daywalker_alerts WHERE symbol = 'DWA'")
                .query(Long.class).single();
        assertThat(count).isEqualTo(1L); // updated, not duplicated
    }

    @Test
    void eventTypeIsPersistedAndSameDayUpdateKeepsItWhenNull() {
        var item = watchlist.insert("default", "DWA", "Daywalker Test A",
                50.0, List.of(50.0), "", null, null);

        alerts.insert("default", item.id(), "DWA", "NEGATIVE_NEWS",
                "WARNING", "guidance cut headline", new BigDecimal("0.700"),
                "run-et-1", false, "guidance_cut");
        assertThat(jdbc.sql("SELECT event_type FROM daywalker_alerts WHERE symbol = 'DWA'")
                .query(String.class).single()).isEqualTo("guidance_cut");

        var sameDay = alerts.findSameUtcDay("default", "DWA", "NEGATIVE_NEWS",
                java.time.Instant.now()).orElseThrow();

        // A daywalker-deep escalation verdict has NO event_type (its schema is not
        // extended): null must KEEP the stored value — COALESCE lives in the SQL,
        // only a real-DB test can prove it (spec §6, R2-M3).
        alerts.updateSameDayAlert(sameDay.id(), "NEGATIVE_NEWS", "CRITICAL",
                "deep second opinion", new BigDecimal("0.900"), "run-et-2", false, null);
        assertThat(jdbc.sql("SELECT event_type FROM daywalker_alerts WHERE symbol = 'DWA'")
                .query(String.class).single()).isEqualTo("guidance_cut");

        // With a value: overwrites.
        alerts.updateSameDayAlert(sameDay.id(), "NEGATIVE_NEWS", "CRITICAL",
                "corrected category", new BigDecimal("0.900"), "run-et-3", false, "other");
        assertThat(jdbc.sql("SELECT event_type FROM daywalker_alerts WHERE symbol = 'DWA'")
                .query(String.class).single()).isEqualTo("other");
    }

    @Test
    void legacyInsertOverloadLeavesEventTypeNull() {
        var item = watchlist.insert("default", "DWA", "Daywalker Test A",
                50.0, List.of(50.0), "", null, null);
        alerts.insert("default", item.id(), "DWA", "PRICE_SPIKE",
                "INFO", "no event type", new BigDecimal("0.500"), "run-et-4");
        Long nulls = jdbc.sql("SELECT COUNT(*) FROM daywalker_alerts "
                + "WHERE symbol = 'DWA' AND event_type IS NULL").query(Long.class).single();
        assertThat(nulls).isEqualTo(1L);
    }
}
