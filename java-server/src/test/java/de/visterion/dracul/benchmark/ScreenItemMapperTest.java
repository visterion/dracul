package de.visterion.dracul.benchmark;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenItemMapperTest {

    private final JsonMapper json = new JsonMapper();

    private JsonNode node(String s) {
        return json.readTree(s);
    }

    @Test
    void mergerItemWithBlankSymbolTakesTheTickerFromCompanyNameAndKeepsTheOffer() {
        Optional<ScreenItem> item = ScreenItemMappers.MERGER.map(node("""
                {"symbol":"","companyName":"Synthetic Corp (SYNA, SYNA-PA)","offerPrice":52.0,"spreadPercent":3.25}"""));
        assertThat(item).isPresent();
        assertThat(item.get().symbol()).isEqualTo("SYNA");
        assertThat(item.get().score()).isEqualByComparingTo("3.25");
        assertThat(item.get().extra().path("offerPrice").decimalValue()).isEqualByComparingTo("52.0");
        assertThat(item.get().extra().has("offerCurrency")).isFalse();
    }

    @Test
    void mergerOfferCurrencyIsKeptWhenTheRawItemCarriesOne() {
        ScreenItem item = ScreenItemMappers.MERGER.map(node("""
                {"symbol":"SYNA","offerPrice":"52.5","offerCurrency":"usd"}""")).orElseThrow();
        assertThat(item.extra().path("offerPrice").decimalValue()).isEqualByComparingTo("52.5");
        assertThat(item.extra().path("offerCurrency").asString()).isEqualTo("USD");
    }

    @Test
    void mergerWithoutOfferHasNoExtra() {
        ScreenItem item = ScreenItemMappers.MERGER.map(node("""
                {"symbol":"SYNA","offerPrice":null}""")).orElseThrow();
        assertThat(item.extra()).isNull();
    }

    @Test
    void insiderReadsTheTickerField() {
        ScreenItem item = ScreenItemMappers.INSIDER.map(node("""
                {"ticker":"synb","companyName":"Synthetic B","totalDollarValue":750000}""")).orElseThrow();
        assertThat(item.symbol()).isEqualTo("SYNB");
        assertThat(item.score()).isEqualByComparingTo("750000");
        assertThat(item.extra()).isNull();
    }

    @Test
    void eachHunterReadsItsOwnScoreField() {
        assertThat(ScreenItemMappers.ECHO.map(node("{\"symbol\":\"SYN1\",\"sue\":1.8}")).orElseThrow().score())
                .isEqualByComparingTo("1.8");
        assertThat(ScreenItemMappers.LAZARUS.map(node("{\"symbol\":\"SYN3.HK\",\"fScore\":7}")).orElseThrow().score())
                .isEqualByComparingTo("7");
        assertThat(ScreenItemMappers.SPIN.map(node("{\"symbol\":\"SYN1\",\"sizeRatio\":0.12}")).orElseThrow().score())
                .isEqualByComparingTo("0.12");
        assertThat(ScreenItemMappers.INDEX.map(node("{\"symbol\":\"SYN1\",\"demandToAdvRatioEstimate\":2.5}"))
                .orElseThrow().score()).isEqualByComparingTo("2.5");
    }

    @Test
    void nonNumericMissingOrNanScoreIsNull() {
        assertThat(ScreenItemMappers.ECHO.map(node("{\"symbol\":\"SYNC\",\"sue\":\"n/a\"}")).orElseThrow().score()).isNull();
        assertThat(ScreenItemMappers.ECHO.map(node("{\"symbol\":\"SYNC\",\"sue\":null}")).orElseThrow().score()).isNull();
        assertThat(ScreenItemMappers.ECHO.map(node("{\"symbol\":\"SYNC\"}")).orElseThrow().score()).isNull();
        assertThat(ScreenItemMappers.ECHO.map(json.valueToTree(new Sue("SYNC", Double.NaN))).orElseThrow().score()).isNull();
    }

    record Sue(String symbol, Double sue) {}

    @Test
    void symbolLessOrNonObjectItemIsEmpty() {
        assertThat(ScreenItemMappers.SPIN.map(node("""
                {"symbol":null,"companyName":"Synthetic Holdings  (CIK 0000000001)"}"""))).isEmpty();
        assertThat(ScreenItemMappers.SPIN.map(node("\"SYNA\""))).isEmpty();
        assertThat(ScreenItemMappers.SPIN.map(null)).isEmpty();
    }

    @Test
    void forAgentKnowsExactlyTheSixCaptureHunters() {
        assertThat(ScreenItemMappers.captureAgents()).isEqualTo(Set.of("strigoi-echo", "strigoi-lazarus",
                "strigoi-merger", "strigoi-insider", "strigoi-spin", "strigoi-index"));
        assertThat(ScreenItemMappers.forAgent("strigoi-merger")).containsSame(ScreenItemMappers.MERGER);
        assertThat(ScreenItemMappers.forAgent("strigoi-tech")).isEmpty();
        assertThat(ScreenItemMappers.forAgent("strigoi-momentum")).isEmpty();
        assertThat(ScreenItemMappers.forAgent(null)).isEmpty();
    }
}
