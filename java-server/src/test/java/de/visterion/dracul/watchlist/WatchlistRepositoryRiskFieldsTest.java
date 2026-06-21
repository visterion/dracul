package de.visterion.dracul.watchlist;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class WatchlistRepositoryRiskFieldsTest {

    @Autowired WatchlistRepository repo;

    @Test
    void entryDateBackfilledAndStopFreezesOnce() {
        var item = repo.insert("u-risk", "RSK", "Risk Co", 10.0, java.util.List.of(),
                "HELD", null, "EUR");
        String id = item.id();

        var risk = repo.positionRiskByItemId().get(id);
        assertThat(risk).isNotNull();
        assertThat(risk.entryDate()).isNotNull();      // default CURRENT_DATE
        assertThat(risk.initialStop()).isNull();

        assertThat(repo.updateInitialStop(id, new BigDecimal("8.5000"))).isTrue();
        assertThat(repo.positionRiskByItemId().get(id).initialStop())
                .isEqualByComparingTo("8.5000");

        // second freeze is a no-op (initial_stop already set)
        assertThat(repo.updateInitialStop(id, new BigDecimal("7.0000"))).isFalse();
        assertThat(repo.positionRiskByItemId().get(id).initialStop())
                .isEqualByComparingTo("8.5000");

        assertThat(repo.updateEntryDate(id, "2024-01-15")).isTrue();
        assertThat(repo.positionRiskByItemId().get(id).entryDate()).isEqualTo("2024-01-15");
    }
}
