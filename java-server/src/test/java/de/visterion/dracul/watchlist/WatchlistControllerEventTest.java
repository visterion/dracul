package de.visterion.dracul.watchlist;

import de.visterion.dracul.auth.CurrentUserHolder;
import de.visterion.dracul.marketdata.MarketDataPort;
import de.visterion.dracul.verdict.VerdictRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WatchlistControllerEventTest {

    private WatchlistRepository repo;
    private MarketDataPort marketData;
    private VerdictRepository verdictRepo;
    private ApplicationEventPublisher events;
    private WatchlistController controller;

    private static final String USER = "default";

    @BeforeEach
    void setUp() {
        repo = mock(WatchlistRepository.class);
        marketData = mock(MarketDataPort.class);
        verdictRepo = mock(VerdictRepository.class);
        events = mock(ApplicationEventPublisher.class);
        controller = new WatchlistController(repo, marketData, verdictRepo, events);
        CurrentUserHolder.set(USER);
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clear();
    }

    private WatchlistItem heldItem(String id) {
        return new WatchlistItem(id, "ACME", "Acme", 100.0, 0.0, "calm",
                "2026-06-01", "HELD", null, List.of(), List.of(100.0),
                90.0, 10.0, USER);
    }

    @Test
    void positionUpdatePublishesChangedEvent() {
        var item = heldItem("11111111-1111-1111-1111-111111111111");
        when(repo.findById(item.id())).thenReturn(Optional.of(item));
        when(repo.updatePosition(item.id(), 90.0, 10.0)).thenReturn(true);

        controller.patchPosition(item.id(), new PatchPositionRequest(90.0, 10.0));

        verify(events).publishEvent(any(WatchlistChangedEvent.class));
    }
}
