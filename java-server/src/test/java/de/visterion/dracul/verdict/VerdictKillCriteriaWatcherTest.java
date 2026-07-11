package de.visterion.dracul.verdict;

import de.visterion.dracul.criteria.KillCriteriaEvaluator;
import de.visterion.dracul.events.VerdictKillCriteriaBreachedEvent;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VerdictKillCriteriaWatcherTest {

    @Mock VerdictRepository verdictRepo;
    @Mock WatchlistRepository watchlistRepo;
    @Mock PreyRepository preyRepo;
    @Mock AgoraMarketData marketData;
    @Mock ApplicationEventPublisher events;

    VerdictKillCriteriaWatcher watcher;

    @BeforeEach
    void setUp() {
        watcher = new VerdictKillCriteriaWatcher(
                verdictRepo, watchlistRepo, preyRepo, marketData,
                new KillCriteriaEvaluator(), events);
    }

    private Prey preyWithKillCriteria(String id, List<String> killCriteria) {
        return new Prey(id, "ABC", "ABC Corp", "SPINOFF", 0.7, "thesis",
                List.of(), List.of(), killCriteria, "6m", "strigoi-spin", "2026-01-01T00:00:00Z");
    }

    @Test
    void breachOnWatchedVerdict_persistsAndPublishes() {
        when(verdictRepo.findOpenForKillCheck()).thenReturn(List.of(
                new VerdictRepository.OpenVerdictForCheck("v1", "u1", "ABC", List.of("p1"), List.of())));
        when(watchlistRepo.findAll()).thenReturn(List.of()); // nothing held
        when(preyRepo.findByIds(List.of("p1"))).thenReturn(List.of(preyWithKillCriteria("p1", List.of("Close below 90"))));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of("ABC", new Quote(new BigDecimal("85"), null)));

        watcher.poll();

        verify(verdictRepo).markKillCriteriaBreached("v1", List.of("Close below 90"));
        verify(events).publishEvent(argThat((Object e) -> e instanceof VerdictKillCriteriaBreachedEvent b
                && b.verdictId().equals("v1") && b.breached().contains("Close below 90")));
    }

    @Test
    void heldSymbolsAreSkipped() {
        when(verdictRepo.findOpenForKillCheck()).thenReturn(List.of(
                new VerdictRepository.OpenVerdictForCheck("v1", "u1", "ABC", List.of("p1"), List.of())));
        WatchlistItem held = new WatchlistItem(
                "w1", "ABC", "ABC Corp", 85.0, 0.0, "calm", "2026-01-01", "HELD",
                null, List.of(), List.of(), 100.0, 10.0, "u1", "USD", "USD");
        when(watchlistRepo.findAll()).thenReturn(List.of(held));

        watcher.poll();

        verify(marketData, never()).quotes(anyCollection());
        verify(verdictRepo, never()).markKillCriteriaBreached(anyString(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void alreadyBreachedCriteriaDoNotRepublish() {
        when(verdictRepo.findOpenForKillCheck()).thenReturn(List.of(
                new VerdictRepository.OpenVerdictForCheck("v1", "u1", "ABC", List.of("p1"), List.of("Close below 90"))));
        when(watchlistRepo.findAll()).thenReturn(List.of());
        when(preyRepo.findByIds(List.of("p1"))).thenReturn(List.of(preyWithKillCriteria("p1", List.of("Close below 90"))));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of("ABC", new Quote(new BigDecimal("85"), null)));

        watcher.poll();

        verify(verdictRepo).markKillCriteriaBreached("v1", List.of("Close below 90"));
        verify(events, never()).publishEvent(any());
    }
}
