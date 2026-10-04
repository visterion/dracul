package de.visterion.dracul.strigoi.tech;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.InstrumentSearchHit;
import de.visterion.dracul.marketdata.InstrumentSearchService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** check_tech_candidate's facts and the F4 source-outage discriminator (synthetic data only). */
class TechCandidateServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AgoraClient agora = mock(AgoraClient.class);
    private final AgoraCompanyData companyData = mock(AgoraCompanyData.class);
    private final InstrumentSearchService search = mock(InstrumentSearchService.class);
    private final TechCandidateService service = new TechCandidateService(agora, companyData, search,
            new TechSettings(12, 3, new BigDecimal("0.033"), new BigDecimal("20000"), 90,
                    "depot-1", "depot-1", "USD"));

    private static final TechBookService.Snapshot EMPTY = new TechBookService.Snapshot(true,
            List.of(), List.of(), 0, Set.of(), Set.of(), Set.of());

    @Test
    void aConfirmedLargeEquityIsEligibleAndThePayloadCarriesTheFacts() {
        when(companyData.profileStrict("SYNA")).thenReturn(JSON.readTree("""
                {"name":"Synthetic A","finnhubIndustry":"Media","marketCapitalization":50000,
                 "currency":"USD","exchange":"SYNTHETIC EXCHANGE","ticker":"SYNA"}"""));
        when(search.search(eq("SYNA"), anyInt()))
                .thenReturn(List.of(new InstrumentSearchHit("SYNA", "Synthetic A", "SYN", "EQUITY")));
        when(agora.callTool(eq("get_quote"), any())).thenReturn(JSON.readTree("""
                {"quotes":[{"symbol":"SYNA","price":120.5,"currency":"USD"}]}"""));
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(JSON.readTree("""
                {"currentClose":120.5,"values":[
                  {"label":"atr","available":true,"value":3.1},
                  {"label":"ma50","available":true,"value":110},
                  {"label":"ma200","available":false,"value":null},
                  {"label":"52w_range","available":true,"value":{"high":130,"low":80}}]}"""));

        TechCandidateService.Candidate c = service.check("SYNA", EMPTY);

        assertThat(c.verdict().eligible()).isTrue();
        assertThat(c.sourceUnavailable()).isFalse();
        assertThat(c.payload().path("profile").path("industry").asString()).isEqualTo("Media");
        assertThat(c.payload().path("profile").path("type").asString()).isEqualTo("EQUITY");
        assertThat(c.payload().path("quote").path("price").decimalValue()).isEqualByComparingTo("120.5");
        assertThat(c.payload().path("technicals").path("ma50").decimalValue()).isEqualByComparingTo("110");
        assertThat(c.payload().path("technicals").has("ma200")).isFalse();
        assertThat(c.payload().path("technicals").path("high_52w").decimalValue()).isEqualByComparingTo("130");
        assertThat(c.payload().path("eligible").asBoolean()).isTrue();
    }

    /** Ruling F4: profile AND quote both failed because Agora never answered -> sourceUnavailable. */
    @Test
    void profileAndQuoteBothDownIsASourceOutage() {
        when(companyData.profileStrict("SYNA"))
                .thenThrow(new AgoraUnavailableException("Agora unreachable for get_company_profile"));
        when(agora.callTool(eq("get_quote"), any()))
                .thenThrow(new AgoraUnavailableException("Agora unreachable for get_quote"));
        when(agora.callTool(eq("get_indicators"), any()))
                .thenThrow(new AgoraUnavailableException("Agora unreachable for get_indicators"));
        when(search.search(eq("SYNA"), anyInt())).thenReturn(List.of());

        TechCandidateService.Candidate c = service.check("SYNA", EMPTY);

        assertThat(c.sourceUnavailable()).isTrue();
        assertThat(c.verdict().eligible()).isFalse();
        assertThat(c.verdict().reasons()).contains("data_unavailable:profile");
    }

    /** An error envelope about this one symbol (REQUEST scope, e.g. an unknown ticker) is not an
     *  outage: one bad pick must never flip the whole run to "unavailable". */
    @Test
    void requestScopedFailuresAreNotASourceOutage() {
        when(companyData.profileStrict("SYNX")).thenThrow(new AgoraUnavailableException(
                AgoraUnavailableException.Scope.REQUEST, "unknown symbol", null));
        when(agora.callTool(eq("get_quote"), any())).thenThrow(new AgoraUnavailableException(
                AgoraUnavailableException.Scope.REQUEST, "unknown symbol", null));
        when(agora.callTool(eq("get_indicators"), any())).thenThrow(new AgoraUnavailableException(
                AgoraUnavailableException.Scope.REQUEST, "unknown symbol", null));

        TechCandidateService.Candidate c = service.check("SYNX", EMPTY);

        assertThat(c.sourceUnavailable()).isFalse();
        assertThat(c.verdict().eligible()).isFalse();
    }

    /** Only one of the two down is not an outage either (the other half answered). */
    @Test
    void onlyTheQuoteDownIsNotASourceOutage() {
        when(companyData.profileStrict("SYNA")).thenReturn(JSON.readTree("{\"ticker\":\"SYNA\"}"));
        when(agora.callTool(eq("get_quote"), any()))
                .thenThrow(new AgoraUnavailableException("Agora unreachable for get_quote"));
        when(agora.callTool(eq("get_indicators"), any()))
                .thenThrow(new AgoraUnavailableException("Agora unreachable for get_indicators"));

        TechCandidateService.Candidate c = service.check("SYNA", EMPTY);

        assertThat(c.sourceUnavailable()).isFalse();
        assertThat(c.verdict().reasons()).contains("data_unavailable:quote_currency");
    }

    /** Ruling F7: search_instruments ignores 1-letter queries, so the type stays unknown. */
    @Test
    void aOneLetterTickerIsDataUnavailableInstrumentType() {
        when(companyData.profileStrict("Q")).thenReturn(JSON.readTree(
                "{\"ticker\":\"Q\",\"marketCapitalization\":50000}"));
        when(search.search(eq("Q"), anyInt())).thenReturn(List.of());   // real service: < 2 chars
        when(agora.callTool(eq("get_quote"), any())).thenReturn(JSON.readTree(
                "{\"quotes\":[{\"symbol\":\"Q\",\"price\":10,\"currency\":\"USD\"}]}"));

        assertThat(service.eligibility("Q", EMPTY).reasons())
                .containsExactly("data_unavailable:instrument_type");
    }
}
