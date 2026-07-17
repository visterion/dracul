package de.visterion.dracul.verdict;

import de.visterion.dracul.criteria.KillCriteriaEvaluator;
import de.visterion.dracul.events.VerdictKillCriteriaBreachedEvent;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VerdictKillCriteriaWatcherTest {

    private static final String CONNECTION = "depot-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock HeldPositionService heldPositions;
    @Mock VerdictRepository verdictRepo;
    @Mock AgoraMarketData marketData;
    @Mock ApplicationEventPublisher events;

    VerdictKillCriteriaWatcher watcher;

    @BeforeEach
    void setUp() {
        watcher = new VerdictKillCriteriaWatcher(
                heldPositions, verdictRepo, marketData, new KillCriteriaEvaluator(), events,
                CONNECTION, "");
    }

    private static JsonNode criteria(String... criteria) {
        return MAPPER.valueToTree(List.of(criteria));
    }

    private static HeldPosition held(String symbol, String verdictId, JsonNode killCriteria) {
        return new HeldPosition(symbol, new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("1000"), new BigDecimal("0"), null, verdictId, killCriteria, "6m", null,
                null, null, "reconcile", "2026-01-01T00:00:00Z");
    }

    @Test
    void breachOnHeldPositionPersistsAndPublishes() {
        var position = held("ABC", "v1", criteria("Close below 90"));
        when(heldPositions.openPositions(CONNECTION)).thenReturn(List.of(position));
        when(verdictRepo.killCriteriaBreachedFor("v1")).thenReturn(List.of());
        when(marketData.quotes(anyCollection())).thenReturn(Map.of("ABC", new Quote(new BigDecimal("85"), null)));

        watcher.poll();

        verify(verdictRepo).markKillCriteriaBreached("v1", List.of("Close below 90"));
        verify(events).publishEvent(argThat((Object e) -> e instanceof VerdictKillCriteriaBreachedEvent b
                && b.verdictId().equals("v1") && b.breached().contains("Close below 90")));
    }

    @Test
    void nullKillCriteriaSkipsPositionWithoutError() {
        var position = held("ABC", "v1", null);
        when(heldPositions.openPositions(CONNECTION)).thenReturn(List.of(position));

        watcher.poll();   // must not throw

        verify(marketData, never()).quotes(anyCollection());
        verify(verdictRepo, never()).markKillCriteriaBreached(anyString(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void positionWithoutLinkedVerdictSkipsWithoutError() {
        var position = held("ABC", null, criteria("Close below 90"));
        when(heldPositions.openPositions(CONNECTION)).thenReturn(List.of(position));

        watcher.poll();   // must not throw

        verify(marketData, never()).quotes(anyCollection());
        verify(verdictRepo, never()).markKillCriteriaBreached(anyString(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void depotUnavailableYieldsEmptyPositionsAndNoOp() {
        when(heldPositions.openPositions(CONNECTION)).thenReturn(List.of());

        watcher.poll();

        verify(marketData, never()).quotes(anyCollection());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void alreadyBreachedCriteriaDoNotRepublish() {
        var position = held("ABC", "v1", criteria("Close below 90"));
        when(heldPositions.openPositions(CONNECTION)).thenReturn(List.of(position));
        when(verdictRepo.killCriteriaBreachedFor("v1")).thenReturn(List.of("Close below 90"));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of("ABC", new Quote(new BigDecimal("85"), null)));

        watcher.poll();

        verify(verdictRepo).markKillCriteriaBreached("v1", List.of("Close below 90"));
        verify(events, never()).publishEvent(any());
    }

    @Test
    void breachesAreCumulative_priceRecoveryDoesNotUnbreach() {
        // Already breached "Close below 90"; price has recovered to 95 (fresh evaluation empty)
        // => the persisted breach stays (badge persists), and nothing is republished.
        var position = held("ABC", "v1", criteria("Close below 90"));
        when(heldPositions.openPositions(CONNECTION)).thenReturn(List.of(position));
        when(verdictRepo.killCriteriaBreachedFor("v1")).thenReturn(List.of("Close below 90"));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of("ABC", new Quote(new BigDecimal("95"), null)));

        watcher.poll();

        verify(verdictRepo).markKillCriteriaBreached("v1", List.of("Close below 90"));
        verify(events, never()).publishEvent(any());
    }

    @Test
    void breachesAreCumulative_newBreachIsAppendedAndOnlyItIsPublished() {
        // Already breached "Close below 100"; the fresh evaluation additionally breaches
        // "Close below 90" => persisted union is ["Close below 100","Close below 90"],
        // and only the new criterion is published.
        var position = held("ABC", "v1", criteria("Close below 100", "Close below 90"));
        when(heldPositions.openPositions(CONNECTION)).thenReturn(List.of(position));
        when(verdictRepo.killCriteriaBreachedFor("v1")).thenReturn(List.of("Close below 100"));
        when(marketData.quotes(anyCollection())).thenReturn(Map.of("ABC", new Quote(new BigDecimal("85"), null)));

        watcher.poll();

        verify(verdictRepo).markKillCriteriaBreached("v1", List.of("Close below 100", "Close below 90"));
        verify(events).publishEvent(argThat((Object e) -> e instanceof VerdictKillCriteriaBreachedEvent b
                && b.breached().equals(List.of("Close below 90"))));
    }
}
