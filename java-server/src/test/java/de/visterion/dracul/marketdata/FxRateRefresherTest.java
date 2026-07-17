package de.visterion.dracul.marketdata;

import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.verdict.VerdictRepository;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;

class FxRateRefresherTest {

    private static HeldPosition depotPos(String symbol, String ccy) {
        return new HeldPosition(symbol, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ZERO, ccy, null, null, null, null, null, null, null, null);
    }

    @Test
    void warmsEachNonDisplayCurrencyOnceIncludingDepotPositions() {
        FxService fx = mock(FxService.class);
        AppSettingsRepository settings = mock(AppSettingsRepository.class);
        WatchlistRepository watchlist = mock(WatchlistRepository.class);
        VerdictRepository verdicts = mock(VerdictRepository.class);
        HeldPositionService heldPositions = mock(HeldPositionService.class);

        when(settings.getDisplayCurrency()).thenReturn("EUR");
        when(watchlist.distinctCurrencies()).thenReturn(List.of("USD", "EUR", "HKD"));
        when(verdicts.distinctCurrencies()).thenReturn(List.of("USD", "CHF"));
        // depot positions add NOK (and USD again, deduped); null currency is skipped
        when(heldPositions.openPositions("depot-1")).thenReturn(List.of(
                depotPos("AAA", "NOK"), depotPos("BBB", "USD"), depotPos("CCC", null)));

        new FxRateRefresher(fx, settings, watchlist, verdicts, heldPositions, "depot-1").refresh();

        verify(fx).warm("USD", "EUR");
        verify(fx).warm("HKD", "EUR");
        verify(fx).warm("CHF", "EUR");
        verify(fx).warm("NOK", "EUR");
        verify(fx, never()).warm("EUR", "EUR");
        verifyNoMoreInteractions(fx);
    }
}
