package de.visterion.dracul.strigoi.tech;

import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionFixtures;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignal;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.executor.ExitProfile;
import de.visterion.dracul.position.HeldPositionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TechBookServiceTest {

    /** Wednesday — the ISO week started Monday 2026-07-06 00:00 UTC. */
    private static final Instant NOW = Instant.parse("2026-07-08T22:30:00Z");

    private final ExecutorPositionRepository positions = mock(ExecutorPositionRepository.class);
    private final ExecutorSignalRepository signals = mock(ExecutorSignalRepository.class);
    private final HeldPositionService held = mock(HeldPositionService.class);
    private final TechSettings settings = new TechSettings(12, 3, new BigDecimal("0.033"),
            new BigDecimal("20000"), 90, "depot-1", "depot-1", "USD");

    private TechBookService service(boolean executor) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(executor
                ? Map.of("positions", positions, "signals", signals) : Map.of());
        return new TechBookService(beans.getBeanProvider(ExecutorPositionRepository.class),
                beans.getBeanProvider(ExecutorSignalRepository.class), held, settings,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExecutorPosition open(long id, String symbol, ExitProfile profile) {
        return ExecutorPositionFixtures.withProfileFields(ExecutorPositionFixtures.withoutKillLevel(
                id, "depot-1", symbol, "BUY", BigDecimal.TEN, new BigDecimal("100"),
                new BigDecimal("65"), new BigDecimal("65"), 1, null, List.of(), "sig-" + id,
                "strigoi-tech", "2026-07-01", null, "OPEN", null, null, null, 0, null, null, null,
                null, null, null, null, null, null, 0, null, null, null, null, null, null, false,
                null, null), profile, null, null, null, false);
    }

    private static ExecutorSignal pending(String id, String symbol, String mechanism) {
        return new ExecutorSignal(id, "strigoi-tech", "v1", symbol, "BUY", 0.8, mechanism,
                List.of("x"), "12m", new BigDecimal("100"), "PENDING", "2026-07-08T22:30:00Z");
    }

    /** Capacity = min(basket − open CONVICTION − pending tech, week max − ACCEPTED this week −
     *  pending tech); the weekly cap counts ACCEPTED (placed) entries and PENDING tech signals. */
    @Test
    void capacityCountsAcceptedThisWeekAndPendingTechSignals() {
        when(positions.findOpen()).thenReturn(List.of(open(1L, "SYNA", ExitProfile.CONVICTION),
                open(2L, "SYNB", ExitProfile.STANDARD)));
        when(signals.findPending(anyInt())).thenReturn(List.of(
                pending("s1", "SYNC", "TECH_CONVICTION"), pending("s2", "SYND", "PEAD")));
        when(signals.countByMechanismAndStatusSince(eq("TECH_CONVICTION"), eq("ACCEPTED"), any()))
                .thenReturn(1);
        when(positions.findSymbolsClosedSince(eq(ExitProfile.CONVICTION), any())).thenReturn(Set.of("SYNE"));

        TechBookService.Snapshot s = service(true).snapshot();

        assertThat(s.slotsFree(12)).isEqualTo(10);          // 12 - 1 - 1
        assertThat(s.newAllowedThisWeek(3)).isEqualTo(1);   // 3 - 1 - 1
        assertThat(s.capacity(12, 3)).isEqualTo(1);
        assertThat(s.held("syna")).isTrue();
        assertThat(s.held("SYNB")).isTrue();                // any executor position counts as held
        assertThat(s.pending("SYND")).isTrue();
        assertThat(s.recentlyExited("SYNE")).isTrue();
        verify(signals).countByMechanismAndStatusSince("TECH_CONVICTION", "ACCEPTED",
                Instant.parse("2026-07-06T00:00:00Z"));
        verify(positions).findSymbolsClosedSince(ExitProfile.CONVICTION,
                NOW.minus(java.time.Duration.ofDays(90)));
    }

    @Test
    void withoutTheExecutorOnlyDepotHoldingsCount() {
        when(held.openPositions("depot-1")).thenReturn(List.of());

        TechBookService.Snapshot s = service(false).snapshot();

        assertThat(s.executorAvailable()).isFalse();
        assertThat(s.capacity(12, 3)).isEqualTo(3);
    }
}
