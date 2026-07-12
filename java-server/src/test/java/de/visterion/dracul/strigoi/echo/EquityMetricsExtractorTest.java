package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EquityMetricsExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void extractsBetaCapRangeAndSector() {
        AgoraCompanyData d = mock(AgoraCompanyData.class);
        when(d.fundamentals("ACME")).thenReturn(mapper.readTree(
                "{\"beta\":1.1,\"marketCapitalization\":2500000,\"52WeekLow\":150.0,\"52WeekHigh\":260.0}"));
        when(d.profile("ACME")).thenReturn(mapper.readTree(
                "{\"name\":\"Acme Inc\",\"finnhubIndustry\":\"Technology\"}"));
        EquityMetrics m = new EquityMetricsExtractor(d).metrics("ACME");
        assertThat(m.available()).isTrue();
        assertThat(m.beta()).isEqualTo(1.1);
        assertThat(m.marketCap()).isEqualTo(2_500_000.0);
        assertThat(m.week52Low()).isEqualTo(150.0);
        assertThat(m.week52High()).isEqualTo(260.0);
        assertThat(m.sector()).isEqualTo("Technology");
    }

    @Test void nullFundamentalsIsUnavailable() {
        AgoraCompanyData d = mock(AgoraCompanyData.class);
        when(d.fundamentals("ACME")).thenReturn(null);
        assertThat(new EquityMetricsExtractor(d).metrics("ACME").available()).isFalse();
    }

    @Test void missingProfileDegradesToNullSectorOnly() {
        AgoraCompanyData d = mock(AgoraCompanyData.class);
        when(d.fundamentals("ACME")).thenReturn(mapper.readTree("{\"beta\":1.1}"));
        when(d.profile("ACME")).thenReturn(null);
        EquityMetrics m = new EquityMetricsExtractor(d).metrics("ACME");
        assertThat(m.available()).isTrue();
        assertThat(m.beta()).isEqualTo(1.1);
        assertThat(m.marketCap()).isNull();   // absent metric -> null, not 0
        assertThat(m.sector()).isNull();
    }

    @Test void metricsWithoutSectorParsesBetaCapRangeButNullSectorAndSkipsProfile() {
        AgoraCompanyData d = mock(AgoraCompanyData.class);
        when(d.fundamentals("ACME")).thenReturn(mapper.readTree(
                "{\"beta\":1.1,\"marketCapitalization\":2500000,\"52WeekLow\":150.0,\"52WeekHigh\":260.0}"));
        EquityMetrics m = new EquityMetricsExtractor(d).metricsWithoutSector("ACME");
        assertThat(m.available()).isTrue();
        assertThat(m.beta()).isEqualTo(1.1);
        assertThat(m.marketCap()).isEqualTo(2_500_000.0);
        assertThat(m.week52Low()).isEqualTo(150.0);
        assertThat(m.week52High()).isEqualTo(260.0);
        assertThat(m.sector()).isNull();                 // sector never populated
        verify(d).fundamentals("ACME");
        verify(d, never()).profile(any());               // the saved round-trip
    }

    @Test void metricsWithoutSectorNullFundamentalsIsUnavailable() {
        AgoraCompanyData d = mock(AgoraCompanyData.class);
        when(d.fundamentals("ACME")).thenReturn(null);
        assertThat(new EquityMetricsExtractor(d).metricsWithoutSector("ACME").available()).isFalse();
        verify(d, never()).profile(any());
    }
}
