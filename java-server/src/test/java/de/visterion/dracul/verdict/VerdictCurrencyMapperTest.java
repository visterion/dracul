package de.visterion.dracul.verdict;

import de.visterion.dracul.marketdata.FxService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerdictCurrencyMapperTest {

    private VerdictDetail detail(double price, String currency) {
        return new VerdictDetail("id", "AVGO", "Broadcom",
                List.of("strigoi-spin"), 0.8, "summary", "2026-06-20",
                List.of("SPIN"), price, 0.7, "90d",
                List.of(), List.of(), List.of(), null, null, currency);
    }

    @Test
    void convertsCurrentPriceAndPreservesNative() {
        FxService fx = mock(FxService.class);
        when(fx.convert(BigDecimal.valueOf(100.0), "USD", "EUR")).thenReturn(BigDecimal.valueOf(92.0));
        var mapper = new VerdictCurrencyMapper(fx);

        var out = mapper.toDisplay(detail(100.0, "USD"), "EUR");

        assertThat(out.currentPrice()).isEqualTo(92.0);
        assertThat(out.currency()).isEqualTo("EUR");
        assertThat(out.nativeCurrentPrice()).isEqualTo(100.0);
        assertThat(out.nativeCurrency()).isEqualTo("USD");
    }

    @Test
    void identityWhenNativeEqualsDisplay() {
        FxService fx = mock(FxService.class);
        when(fx.convert(BigDecimal.valueOf(50.0), "EUR", "EUR")).thenReturn(BigDecimal.valueOf(50.0));
        var mapper = new VerdictCurrencyMapper(fx);

        var out = mapper.toDisplay(detail(50.0, "EUR"), "EUR");

        assertThat(out.currentPrice()).isEqualTo(50.0);
        assertThat(out.currency()).isEqualTo("EUR");
        assertThat(out.nativeCurrency()).isEqualTo("EUR");
    }

    @Test
    void defaultsNullNativeCurrencyToUsd() {
        FxService fx = mock(FxService.class);
        when(fx.convert(BigDecimal.valueOf(10.0), "USD", "EUR")).thenReturn(BigDecimal.valueOf(9.0));
        var mapper = new VerdictCurrencyMapper(fx);

        var out = mapper.toDisplay(detail(10.0, null), "EUR");

        assertThat(out.nativeCurrency()).isEqualTo("USD");
        assertThat(out.currentPrice()).isEqualTo(9.0);
    }
}
