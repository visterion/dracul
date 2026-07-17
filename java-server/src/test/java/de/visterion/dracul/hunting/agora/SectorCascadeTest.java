package de.visterion.dracul.hunting.agora;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SectorCascadeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final AgoraCompanyData companyData = mock(AgoraCompanyData.class);
    private final SectorCascade cascade = new SectorCascade(companyData, 86400, 3600);

    @Test
    void prefersSectorOverFinnhubIndustryOverGicsSector() {
        when(companyData.profile("A")).thenReturn(MAPPER.readTree(
                "{\"sector\":\"Health Care\",\"finnhubIndustry\":\"Biotechnology\",\"gicsSector\":\"HC\"}"));
        when(companyData.profile("B")).thenReturn(MAPPER.readTree(
                "{\"finnhubIndustry\":\"Biotechnology\",\"gicsSector\":\"HC\"}"));
        when(companyData.profile("C")).thenReturn(MAPPER.readTree("{\"gicsSector\":\"HC\"}"));

        assertThat(cascade.resolve("A")).isEqualTo("Health Care");
        assertThat(cascade.resolve("B")).isEqualTo("Biotechnology");
        assertThat(cascade.resolve("C")).isEqualTo("HC");
    }

    @Test
    void blankFieldsAreSkippedInTheCascade() {
        when(companyData.profile("X")).thenReturn(MAPPER.readTree(
                "{\"sector\":\"  \",\"finnhubIndustry\":\"Semiconductors\"}"));
        assertThat(cascade.resolve("X")).isEqualTo("Semiconductors");
    }

    @Test
    void nullProfileResolvesNullWithoutThrowing() {
        when(companyData.profile("GONE")).thenReturn(null);
        assertThat(cascade.resolve("GONE")).isNull();
    }

    @Test
    void positiveResultIsCached() {
        when(companyData.profile("CACHED")).thenReturn(MAPPER.readTree(
                "{\"sector\":\"Tech\"}"));
        assertThat(cascade.resolve("CACHED")).isEqualTo("Tech");
        assertThat(cascade.resolve("CACHED")).isEqualTo("Tech");
        verify(companyData, times(1)).profile("CACHED");
    }

    @Test
    void negativeResultIsCachedToo() {
        when(companyData.profile("MISS")).thenReturn(null);
        assertThat(cascade.resolve("MISS")).isNull();
        assertThat(cascade.resolve("MISS")).isNull();
        verify(companyData, times(1)).profile("MISS");
    }

    @Test
    void runtimeExceptionDegradesToNull() {
        when(companyData.profile("BOOM")).thenThrow(new RuntimeException("agora down"));
        assertThat(cascade.resolve("BOOM")).isNull();
    }
}
