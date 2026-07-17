package de.visterion.dracul.prey;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PreyLifecycleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 17);

    private Prey preyFixture(String horizon, String discoveredAt) {
        return new Prey(
                UUID.randomUUID().toString(), "ACME", "Acme Corp", "SPIN",
                0.7, "thesis", List.of("signal"), List.of("risk"),
                List.of(), horizon, "strigoi-spin", discoveredAt);
    }

    @Test
    void openHorizonIsIncluded() {
        // discovered today, 90d horizon -> expiry far in the future
        Prey open = preyFixture("90d", "2026-07-17T08:00:00Z");

        List<Prey> result = PreyLifecycle.activeOnly(List.of(open), TODAY);

        assertThat(result).containsExactly(open);
    }

    @Test
    void expiredHorizonIsExcluded() {
        // discovered 2026-01-01, 30d horizon -> expiry 2026-01-31, long before TODAY
        Prey expired = preyFixture("30d", "2026-01-01T08:00:00Z");

        List<Prey> result = PreyLifecycle.activeOnly(List.of(expired), TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    void expiryEqualToTodayIsIncludedBoundary() {
        // discovered 2026-06-17, 30d horizon -> expiry exactly TODAY
        Prey boundary = preyFixture("30d", "2026-06-17T08:00:00Z");

        List<Prey> result = PreyLifecycle.activeOnly(List.of(boundary), TODAY);

        assertThat(result).containsExactly(boundary);
    }

    @Test
    void unparseableHorizonIsIncludedFailOpen() {
        Prey weird = preyFixture("not-a-horizon", "2026-01-01T08:00:00Z");

        List<Prey> result = PreyLifecycle.activeOnly(List.of(weird), TODAY);

        assertThat(result).containsExactly(weird);
    }
}
