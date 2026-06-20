package de.visterion.dracul.watchlist;

import de.visterion.dracul.marketdata.FxService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WatchlistCurrencyMapperTest {

    private WatchlistItem usdItem() {
        // 16-arg back-compat ctor: native price = currentPrice, native currency = currency
        return new WatchlistItem("id", "AVGO", "Broadcom",
                100.0, 1.0, "calm", "2026-06-20", "HELD",
                null, List.of(), List.of(),
                80.0, 5.0, "you@dracul.local", "USD", "USD");
    }

    @Test
    void convertsAndPreservesNative() {
        FxService fx = mock(FxService.class);
        when(fx.convert(BigDecimal.valueOf(100.0), "USD", "EUR")).thenReturn(BigDecimal.valueOf(92.0));
        when(fx.convert(BigDecimal.valueOf(80.0), "USD", "EUR")).thenReturn(BigDecimal.valueOf(73.6));
        var mapper = new WatchlistCurrencyMapper(fx);

        var out = mapper.toDisplay(usdItem(), "EUR");

        assertThat(out.currentPrice()).isEqualTo(92.0);
        assertThat(out.entryPrice()).isEqualTo(73.6);
        assertThat(out.currency()).isEqualTo("EUR");
        assertThat(out.nativeCurrentPrice()).isEqualTo(100.0);
        assertThat(out.nativeCurrency()).isEqualTo("USD");
        assertThat(out.nativeEntryPrice()).isEqualTo(80.0);
    }
}
