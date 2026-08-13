package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.AgoraUnavailableException.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link LazarusListingResolver} decides, for each surviving candidate, which listing its
 * fundamentals actually describe — see {@link ListingResolution}'s Javadoc for the contract this
 * resolver must not violate: {@code US_CONFIRMED} may only ever come from a profile call whose
 * {@code ticker} matches the symbol, never from the mere absence of a foreign currency.
 */
class LazarusListingResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgoraCompanyData companyData;

    @BeforeEach
    void setUp() {
        companyData = mock(AgoraCompanyData.class);
    }

    private LazarusListingResolver resolver(int profileMax) {
        return new LazarusListingResolver(companyData, profileMax);
    }

    /** Candidate carrying a size hope; {@code marketCap} is what {@code listingUnknown} keys on. */
    private static LazarusCandidate candidate(String symbol, String reportingCurrency, Double marketCap) {
        return new LazarusCandidate(symbol, symbol + " Inc", 10.0, 9.0, 40.0, 0.05,
                5.0, 1.8, 0.4, 35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3, marketCap, reportingCurrency);
    }

    private static JsonNode profileWithTicker(String ticker) {
        var node = MAPPER.createObjectNode();
        if (ticker == null) {
            node.putNull("ticker");
        } else {
            node.put("ticker", ticker);
        }
        return node;
    }

    private static AgoraUnavailableException sourceDown(String detail) {
        return new AgoraUnavailableException(Scope.SOURCE, "Agora unreachable: " + detail, null);
    }

    private static AgoraUnavailableException perRequest(String detail) {
        return new AgoraUnavailableException(Scope.REQUEST, "Agora tool error: " + detail, null);
    }

    @Test
    void reportingCurrencyPresent_needsNoProfileCall() {
        LazarusCandidate c = candidate("SYNA", "EUR", 500_000.0);

        LazarusListingResolver.Resolved result = resolver(40).resolve(List.of(c));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).listingResolution()).isEqualTo(ListingResolution.FOREIGN_SUFFIXED);
        verifyNoInteractions(companyData);
    }

    @Test
    void tickerEqualsSymbol_isUsConfirmed() {
        LazarusCandidate c = candidate("SYNTH", null, 500_000.0);
        when(companyData.profileStrict("SYNTH")).thenReturn(profileWithTicker("SYNTH"));

        LazarusListingResolver.Resolved result = resolver(40).resolve(List.of(c));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).listingResolution()).isEqualTo(ListingResolution.US_CONFIRMED);
    }

    @Test
    void tickerEqualsSymbolIgnoringCase_isUsConfirmed() {
        LazarusCandidate c = candidate("SYNTH", null, 500_000.0);
        when(companyData.profileStrict("SYNTH")).thenReturn(profileWithTicker("synth"));

        LazarusListingResolver.Resolved result = resolver(40).resolve(List.of(c));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).listingResolution()).isEqualTo(ListingResolution.US_CONFIRMED);
    }

    @Test
    void tickerDiffers_dropsCandidate() {
        LazarusCandidate c = candidate("SYNTH", null, 500_000.0);
        when(companyData.profileStrict("SYNTH")).thenReturn(profileWithTicker("SYNTH.XX"));

        LazarusListingResolver.Resolved result = resolver(40).resolve(List.of(c));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.foreignListing()).isEqualTo(1);
        assertThat(result.listingUnknown()).isZero();
    }

    @Test
    void blankTicker_isUnknownNotForeign() {
        LazarusCandidate empty = candidate("SYNA", null, 500_000.0);
        LazarusCandidate nullTicker = candidate("SYNB", null, 500_000.0);
        when(companyData.profileStrict("SYNA")).thenReturn(profileWithTicker(""));
        when(companyData.profileStrict("SYNB")).thenReturn(profileWithTicker(null));

        LazarusListingResolver.Resolved result = resolver(40).resolve(List.of(empty, nullTicker));

        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates()).allSatisfy(x ->
                assertThat(x.listingResolution()).isEqualTo(ListingResolution.UNKNOWN));
        assertThat(result.listingUnknown()).isEqualTo(2);
        assertThat(result.foreignListing()).isZero();
    }

    @Test
    void missingProfile_isUnknown() {
        LazarusCandidate c = candidate("SYNTH", null, 500_000.0);
        when(companyData.profileStrict("SYNTH")).thenReturn(null);

        LazarusListingResolver.Resolved result = resolver(40).resolve(List.of(c));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).listingResolution()).isEqualTo(ListingResolution.UNKNOWN);
        assertThat(result.listingUnknown()).isEqualTo(1);
    }

    @Test
    void nullMarketCap_isNotCountedAsUnknown() {
        LazarusCandidate c = candidate("SYNTH", null, null);
        when(companyData.profileStrict("SYNTH")).thenReturn(null);

        LazarusListingResolver.Resolved result = resolver(40).resolve(List.of(c));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).listingResolution()).isEqualTo(ListingResolution.UNKNOWN);
        assertThat(result.listingUnknown()).isZero();
    }

    @Test
    void sourceOutage_stopsCallingAfterGuardTrips() {
        when(companyData.profileStrict(anyString())).thenThrow(sourceDown("timeout"));
        List<LazarusCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add(candidate("SYN" + i, null, 500_000.0));
        }

        LazarusListingResolver.Resolved result = resolver(40).resolve(candidates);

        verify(companyData, times(1)).profileStrict(anyString());
        assertThat(result.candidates()).hasSize(10);
        assertThat(result.candidates()).allSatisfy(x ->
                assertThat(x.listingResolution()).isEqualTo(ListingResolution.UNKNOWN));
        assertThat(result.listingUnknown()).isEqualTo(10);
    }

    @Test
    void perRequestErrorDoesNotDisableTheSource() {
        when(companyData.profileStrict("SYNA")).thenThrow(perRequest("no profile for SYNA"));
        when(companyData.profileStrict("SYNB")).thenReturn(profileWithTicker("SYNB"));

        LazarusListingResolver.Resolved result = resolver(40)
                .resolve(List.of(candidate("SYNA", null, 500_000.0), candidate("SYNB", null, 500_000.0)));

        verify(companyData, times(1)).profileStrict("SYNA");
        verify(companyData, times(1)).profileStrict("SYNB");
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.listingUnknown()).isEqualTo(1);
    }

    @Test
    void profileMaxCapsTheNumberOfCalls() {
        when(companyData.profileStrict(anyString())).thenReturn(profileWithTicker(null));
        List<LazarusCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            candidates.add(candidate("SYN" + i, null, 500_000.0));
        }

        LazarusListingResolver.Resolved result = resolver(3).resolve(candidates);

        verify(companyData, times(3)).profileStrict(anyString());
        assertThat(result.candidates()).hasSize(8);
        assertThat(result.listingUnknown()).isEqualTo(8);
    }
}
