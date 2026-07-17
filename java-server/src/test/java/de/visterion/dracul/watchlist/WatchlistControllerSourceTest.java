package de.visterion.dracul.watchlist;

import de.visterion.dracul.auth.CurrentUserHolder;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.MarketData;
import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.verdict.VerdictRepository;
import de.visterion.dracul.verdict.VerdictDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Task 4: verifies {@link WatchlistController#create} computes `source` from
 * whether a `sourceVerdictId` was supplied on the request, and passes it to
 * {@link WatchlistRepository#insert}.
 */
class WatchlistControllerSourceTest {

    private WatchlistRepository repo;
    private AgoraMarketData marketData;
    private VerdictRepository verdictRepo;
    private ApplicationEventPublisher events;
    private AppSettingsRepository settings;
    private WatchlistCurrencyMapper mapper;
    private WatchlistController controller;

    private static final String USER = "default";

    @BeforeEach
    void setUp() {
        repo = mock(WatchlistRepository.class);
        marketData = mock(AgoraMarketData.class);
        verdictRepo = mock(VerdictRepository.class);
        events = mock(ApplicationEventPublisher.class);
        settings = mock(AppSettingsRepository.class);
        when(settings.getDisplayCurrency()).thenReturn("EUR");
        mapper = mock(WatchlistCurrencyMapper.class);
        when(mapper.toDisplay(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        controller = new WatchlistController(repo, marketData, verdictRepo, events, settings, mapper);
        CurrentUserHolder.set(USER);

        when(repo.findByUserAndTicker(any(), any())).thenReturn(Optional.empty());
        MarketData md = new MarketData("Acme", BigDecimal.valueOf(100.0),
                BigDecimal.ZERO, "USD", List.of(BigDecimal.valueOf(100.0)));
        when(marketData.resolve(any())).thenReturn(md);
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clear();
    }

    @Test
    void createWithNullSourceVerdictIdInsertsWithManualSource() {
        controller.create(new CreateWatchlistRequest("ACME", "TRACKING", null));

        verify(repo).insert(eq(USER), eq("ACME"), any(), anyDouble(), any(),
                eq("TRACKING"), eq("manual"), isNull(), any());
    }

    @Test
    void createWithSourceVerdictIdInsertsWithVerdictSource() {
        String verdictId = "b0000000-0000-0000-0000-000000000001";
        when(verdictRepo.findDetailById(verdictId)).thenReturn(Optional.of(mock(VerdictDetail.class)));

        controller.create(new CreateWatchlistRequest("ACME", "TRACKING", verdictId));

        verify(repo).insert(eq(USER), eq("ACME"), any(), anyDouble(), any(),
                eq("TRACKING"), eq("verdict"), eq(verdictId), any());
    }
}
