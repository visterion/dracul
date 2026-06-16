package de.visterion.dracul.hunting;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DataSourceResultTest {

    @Test void healthyCarriesItemsAndSource() {
        var r = DataSourceResult.healthy("edgar", List.of("a", "b"));
        assertThat(r.items()).containsExactly("a", "b");
        assertThat(r.health().status()).isEqualTo("healthy");
        assertThat(r.health().source()).isEqualTo("edgar");
        assertThat(r.health().detail()).isNull();
        assertThat(r.health().isHealthy()).isTrue();
        assertThat(r.health().checkedAt()).isNotNull();
    }

    @Test void unavailableHasEmptyItemsAndDetail() {
        var r = DataSourceResult.unavailable("finnhub", "finnhub: api key missing");
        assertThat(r.items()).isEmpty();
        assertThat(r.health().status()).isEqualTo("unavailable");
        assertThat(r.health().source()).isEqualTo("finnhub");
        assertThat(r.health().detail()).isEqualTo("finnhub: api key missing");
        assertThat(r.health().isHealthy()).isFalse();
    }
}
