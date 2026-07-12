package de.visterion.dracul.strigoi.lazarus;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class BasicFinancialsExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void extractsTheSameFinnhubKeysTheOldAdapterParsed() {
        JsonNode metrics = mapper.readTree(
                "{\"52WeekLow\":10.0,\"52WeekHigh\":40.0,\"roaTTM\":5.0," +
                "\"currentRatioQuarterly\":1.8,\"totalDebt/totalEquityQuarterly\":0.4," +
                "\"grossMarginTTM\":35.0,\"netProfitMarginTTM\":8.0,\"revenueGrowthTTMYoy\":4.0," +
                "\"epsGrowthTTMYoy\":3.0,\"pbAnnual\":1.2,\"peTTM\":11.0,\"freeCashFlowPerShareTTM\":2.3," +
                "\"marketCapitalization\":900.0}");
        BasicFinancials f = BasicFinancialsExtractor.extract(metrics);
        assertThat(f).isNotNull();
        assertThat(f.week52Low()).isEqualTo(10.0);
        assertThat(f.week52High()).isEqualTo(40.0);
        assertThat(f.roaTtm()).isEqualTo(5.0);
        assertThat(f.currentRatio()).isEqualTo(1.8);
        assertThat(f.debtToEquity()).isEqualTo(0.4);
        assertThat(f.grossMargin()).isEqualTo(35.0);
        assertThat(f.netMargin()).isEqualTo(8.0);
        assertThat(f.revenueGrowthYoy()).isEqualTo(4.0);
        assertThat(f.epsGrowthYoy()).isEqualTo(3.0);
        assertThat(f.priceToBook()).isEqualTo(1.2);
        assertThat(f.peTtm()).isEqualTo(11.0);
        assertThat(f.fcfPerShare()).isEqualTo(2.3);
        assertThat(f.marketCap()).isEqualTo(900.0); // Finnhub USD millions, feeds the Altman-Z X4
    }

    @Test void absentOrNonNumericFieldsAreNullNotZero() {
        JsonNode metrics = mapper.readTree("{\"52WeekLow\":10.0,\"roaTTM\":\"n/a\"}");
        BasicFinancials f = BasicFinancialsExtractor.extract(metrics);
        assertThat(f.week52Low()).isEqualTo(10.0);
        assertThat(f.roaTtm()).isNull();
        assertThat(f.peTtm()).isNull();
        assertThat(f.marketCap()).isNull();
    }

    @Test void nullOrNonObjectInputYieldsNull() {
        assertThat(BasicFinancialsExtractor.extract(null)).isNull();
        assertThat(BasicFinancialsExtractor.extract(mapper.readTree("[1,2]"))).isNull();
    }
}
