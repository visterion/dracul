package de.visterion.dracul.hunting.agora;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SectorResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AgoraCompanyData companyData = mock(AgoraCompanyData.class);

    private SectorResolver resolver(long ttl, long negativeTtl) {
        return new SectorResolver(companyData, ttl, negativeTtl);
    }

    @Test void resolvesFinnhubIndustryAndCachesIt() {
        when(companyData.profile("ACME"))
                .thenReturn(mapper.createObjectNode().put("finnhubIndustry", "Semiconductors"));
        var r = resolver(86400, 3600);
        assertThat(r.sector("ACME")).isEqualTo("Semiconductors");
        assertThat(r.sector("ACME")).isEqualTo("Semiconductors");
        verify(companyData, times(1)).profile("ACME"); // cache hit, no second Agora call
    }

    @Test void failureIsCachedWithNegativeTtl() {
        when(companyData.profile("ACME")).thenReturn(null);
        var r = resolver(86400, 3600);
        assertThat(r.sector("ACME")).isNull();
        assertThat(r.sector("ACME")).isNull();
        verify(companyData, times(1)).profile("ACME"); // no repeat call within the hour
    }

    @Test void missingIndustryFieldIsNull() {
        when(companyData.profile("ACME")).thenReturn(mapper.createObjectNode().put("name", "Acme"));
        assertThat(resolver(86400, 3600).sector("ACME")).isNull();
    }

    @Test void ttlExpiryRefetches() {
        when(companyData.profile("ACME"))
                .thenReturn(mapper.createObjectNode().put("finnhubIndustry", "Semiconductors"));
        var r = resolver(0, 0); // zero TTL: every entry expires immediately
        r.sector("ACME");
        r.sector("ACME");
        verify(companyData, times(2)).profile("ACME");
    }

    @Test void cachedSectorNeverFetches() {
        var r = resolver(86400, 3600);
        assertThat(r.cachedSector("ACME")).isNull();
        verifyNoInteractions(companyData);
        when(companyData.profile("ACME"))
                .thenReturn(mapper.createObjectNode().put("finnhubIndustry", "Semiconductors"));
        r.sector("ACME");
        assertThat(r.cachedSector("ACME")).isEqualTo("Semiconductors");
        verify(companyData, times(1)).profile("ACME");
    }
}
