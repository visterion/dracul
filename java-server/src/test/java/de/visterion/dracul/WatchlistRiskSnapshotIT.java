package de.visterion.dracul;

import de.visterion.dracul.watchlist.PositionRisk;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class WatchlistRiskSnapshotIT {

    @Autowired WatchlistRepository repo;

    @Test
    void snapshotRoundTripsThroughPositionRisk() {
        WatchlistItem item = repo.insert("snap@x.com", "SNAP", "Snap Inc",
                100.0, List.of(), "WATCHED", "manual", null, "USD");
        repo.updateTag(item.id(), "HELD");

        boolean updated = repo.updateRiskSnapshot(item.id(),
                new BigDecimal("182.4000"), new BigDecimal("240.0000"),
                new BigDecimal("178.1000"), null, Instant.parse("2026-06-22T22:00:00Z"));
        assertThat(updated).isTrue();

        PositionRisk pr = repo.positionRiskByItemId().get(item.id());
        assertThat(pr.activeStop()).isEqualByComparingTo("182.40");
        assertThat(pr.nextTarget2r()).isEqualByComparingTo("240.00");
        assertThat(pr.currentClose()).isEqualByComparingTo("178.10");
    }

    @Test
    void secondUpdateOverwritesPriorSnapshot() {
        WatchlistItem item = repo.insert("overwrite@x.com", "OVW", "Overwrite Corp",
                50.0, List.of(), "WATCHED", "manual", null, "USD");
        repo.updateTag(item.id(), "HELD");

        repo.updateRiskSnapshot(item.id(),
                new BigDecimal("100.0000"), new BigDecimal("120.0000"),
                new BigDecimal("110.0000"), null, Instant.parse("2026-06-22T18:00:00Z"));

        boolean updated = repo.updateRiskSnapshot(item.id(),
                new BigDecimal("95.0000"), new BigDecimal("130.0000"),
                new BigDecimal("115.0000"), null, Instant.parse("2026-06-22T22:00:00Z"));
        assertThat(updated).isTrue();

        PositionRisk pr = repo.positionRiskByItemId().get(item.id());
        assertThat(pr.activeStop()).isEqualByComparingTo("95.00");
        assertThat(pr.nextTarget2r()).isEqualByComparingTo("130.00");
        assertThat(pr.currentClose()).isEqualByComparingTo("115.00");
    }

    @Test
    void updateRiskSnapshotRejectsNonUuid() {
        assertThat(repo.updateRiskSnapshot("not-a-uuid", null, null, null, null, Instant.now()))
                .isFalse();
    }

    @Test
    void snapshotPersistsAndReadsAtr() {
        WatchlistItem item = repo.insert("atr@x.com", "ATR", "Atr Inc",
                100.0, List.of(), "WATCHED", "manual", null, "USD");
        repo.updateTag(item.id(), "HELD");

        repo.updateRiskSnapshot(item.id(), new BigDecimal("100.0000"),
                new BigDecimal("130.0000"), new BigDecimal("105.0000"),
                new BigDecimal("8.5000"), Instant.now());

        PositionRisk pr = repo.positionRiskByItemId().get(item.id());
        assertThat(pr.atr()).isEqualByComparingTo("8.5000");
    }
}
