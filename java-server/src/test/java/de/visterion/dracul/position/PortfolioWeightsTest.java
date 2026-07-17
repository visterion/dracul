package de.visterion.dracul.position;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.FxService;
import de.visterion.dracul.settings.AppSettingsRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortfolioWeightsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AppSettingsRepository settings = mock(AppSettingsRepository.class);

    /** Real FxService over a stubbed AgoraClient: convert() falls back to identity on a
     *  cache miss and hasRate() is false — exactly the prod semantics (round 3, F2). */
    private FxService fxWithRate(String from, String to, String rate) {
        AgoraClient agora = mock(AgoraClient.class);
        ObjectNode body = mapper.createObjectNode();
        body.put("rate", rate);
        when(agora.callTool(eq("get_fx_rate"), any())).thenReturn(body);
        FxService fx = new FxService(agora);
        if (from != null) fx.warm(from, to);
        return fx;
    }

    private static HeldPosition pos(String symbol, String qty, String avgPrice, String mv, String ccy) {
        return new HeldPosition(symbol, qty == null ? null : new BigDecimal(qty),
                avgPrice == null ? null : new BigDecimal(avgPrice),
                mv == null ? null : new BigDecimal(mv), BigDecimal.ZERO, ccy,
                null, null, null, null, null, null, null, null);
    }

    @Test void sameCurrencyTwoPositionsAre75And25() {
        when(settings.getDisplayCurrency()).thenReturn("USD");
        var w = new PortfolioWeights(fxWithRate(null, null, null), settings);
        Map<String, BigDecimal> out = w.weightsBySymbol(List.of(
                pos("AAA", "10", "300", "3000", "USD"), pos("BBB", "10", "100", "1000", "USD")));
        assertThat(out.get("AAA")).isEqualByComparingTo("75.0");
        assertThat(out.get("BBB")).isEqualByComparingTo("25.0");
    }

    @Test void mixedCurrencyDepotConvertsViaFx() {
        when(settings.getDisplayCurrency()).thenReturn("EUR");
        // USD->EUR rate 2.0: USD 1000 -> EUR 2000; EUR 2000 stays -> 50/50
        var w = new PortfolioWeights(fxWithRate("USD", "EUR", "2.0"), settings);
        Map<String, BigDecimal> out = w.weightsBySymbol(List.of(
                pos("AAA", "10", "100", "1000", "USD"), pos("BBB", "10", "200", "2000", "EUR")));
        assertThat(out.get("AAA")).isEqualByComparingTo("50.0");
        assertThat(out.get("BBB")).isEqualByComparingTo("50.0");
    }

    @Test void missingRateNullsAllWeights() {
        when(settings.getDisplayCurrency()).thenReturn("EUR");
        // USD rate never warmed: hasRate false -> empty map, NOT identity-converted numbers
        var w = new PortfolioWeights(fxWithRate(null, null, null), settings);
        assertThat(w.weightsBySymbol(List.of(
                pos("AAA", "10", "100", "1000", "USD"), pos("BBB", "10", "200", "2000", "EUR"))))
                .isEmpty();
    }

    @Test void missingMarketValueOrCurrencyNullsAllWeights() {
        when(settings.getDisplayCurrency()).thenReturn("USD");
        var w = new PortfolioWeights(fxWithRate(null, null, null), settings);
        assertThat(w.weightsBySymbol(List.of(
                pos("AAA", "10", "100", null, "USD"), pos("BBB", "10", "100", "1000", "USD")))).isEmpty();
        assertThat(w.weightsBySymbol(List.of(
                pos("AAA", "10", "100", "1000", null), pos("BBB", "10", "100", "1000", "USD")))).isEmpty();
    }

    @Test void blankDisplayCurrencyNullsAllWeights() {
        when(settings.getDisplayCurrency()).thenReturn(" ");
        var w = new PortfolioWeights(fxWithRate(null, null, null), settings);
        assertThat(w.weightsBySymbol(List.of(pos("AAA", "10", "100", "1000", "USD")))).isEmpty();
    }

    @Test void shortPositionUsesAbsoluteMarketValue() {
        when(settings.getDisplayCurrency()).thenReturn("USD");
        var w = new PortfolioWeights(fxWithRate(null, null, null), settings);
        Map<String, BigDecimal> out = w.weightsBySymbol(List.of(
                pos("SHRT", "-10", "100", "-1000", "USD"), pos("LONG", "10", "300", "3000", "USD")));
        assertThat(out.get("SHRT")).isEqualByComparingTo("25.0");
        assertThat(out.get("LONG")).isEqualByComparingTo("75.0");
    }

    @Test void multiLotSymbolWeightSumsItsLots() {
        when(settings.getDisplayCurrency()).thenReturn("USD");
        var w = new PortfolioWeights(fxWithRate(null, null, null), settings);
        Map<String, BigDecimal> out = w.weightsBySymbol(List.of(
                pos("AAPL", "10", "100", "1000", "USD"), pos("AAPL", "10", "100", "1000", "USD"),
                pos("MSFT", "10", "200", "2000", "USD")));
        assertThat(out.get("AAPL")).isEqualByComparingTo("50.0");
        assertThat(out.get("MSFT")).isEqualByComparingTo("50.0");
    }

    @Test void collapseMergesLotsWithWeightedAvgPrice() {
        // two lots, DIFFERENT avgPrices (pins the weighted average): 10@100 + 30@200
        List<HeldPosition> out = PortfolioWeights.collapseBySymbol(List.of(
                pos("AAPL", "10", "100", "1000", "USD"), pos("AAPL", "30", "200", "6000", "USD"),
                pos("MSFT", "5", "50", "250", "USD")));
        assertThat(out).hasSize(2);
        HeldPosition aapl = out.get(0);
        assertThat(aapl.symbol()).isEqualTo("AAPL");
        assertThat(aapl.quantity()).isEqualByComparingTo("40");
        assertThat(aapl.marketValue()).isEqualByComparingTo("7000");
        // (10*100 + 30*200) / 40 = 175
        assertThat(aapl.avgPrice()).isEqualByComparingTo("175");
    }
}
