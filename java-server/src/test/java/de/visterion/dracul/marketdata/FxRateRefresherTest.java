package de.visterion.dracul.marketdata;

import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.verdict.VerdictRepository;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class FxRateRefresherTest {

    @Test
    void warmsEachNonDisplayCurrencyOnce() {
        FxService fx = mock(FxService.class);
        AppSettingsRepository settings = mock(AppSettingsRepository.class);
        WatchlistRepository watchlist = mock(WatchlistRepository.class);
        VerdictRepository verdicts = mock(VerdictRepository.class);

        when(settings.getDisplayCurrency()).thenReturn("EUR");
        when(watchlist.distinctCurrencies()).thenReturn(List.of("USD", "EUR", "HKD"));
        when(verdicts.distinctCurrencies()).thenReturn(List.of("USD", "CHF"));

        new FxRateRefresher(fx, settings, watchlist, verdicts).refresh();

        verify(fx).warm("USD", "EUR");
        verify(fx).warm("HKD", "EUR");
        verify(fx).warm("CHF", "EUR");
        verify(fx, never()).warm("EUR", "EUR");
        verifyNoMoreInteractions(fx);
    }
}
