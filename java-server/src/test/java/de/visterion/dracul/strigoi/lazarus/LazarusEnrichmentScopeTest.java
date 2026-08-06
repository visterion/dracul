package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.AgoraUnavailableException.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A statement about ONE candidate is not a statement about the source (2026-08-06).
 *
 * <p>The F-score and concept sources used to catch {@link AgoraUnavailableException} directly and
 * latch on the FIRST one, so a single per-request error — Agora ANSWERED, with an error envelope
 * about that one symbol — disabled the source for every remaining candidate of the batch. That is
 * the defect the insider hunter was fixed for on the same day
 * ({@code Agora tool error: no CIK for N/A} killed a whole run's enrichment); lazarus carried the
 * identical shape. Both sources now sit behind {@link de.visterion.dracul.strigoi.EnrichmentSourceGuard}.
 */
class LazarusEnrichmentScopeTest {

    private static final FundamentalScore GOOD_SCORE =
            new FundamentalScore(7, 8, BigDecimal.valueOf(0.05), true, true, true);
    private static final AltmanZCalculator.AltmanZ Z_OK =
            new AltmanZCalculator.AltmanZ(new BigDecimal("2.00"), true);

    private AgoraFilings filings;
    private AltmanZCalculator altmanZ;
    private LazarusEnrichmentService service;

    @BeforeEach
    void setUp() {
        filings = mock(AgoraFilings.class);
        AgoraMarketData marketData = mock(AgoraMarketData.class);    // empty bars -> no timing
        altmanZ = mock(AltmanZCalculator.class);
        AgoraCompanyData companyData = mock(AgoraCompanyData.class); // empty trend -> no revisions
        when(altmanZ.zScore(anyString(), any(), any())).thenReturn(AltmanZCalculator.AltmanZ.unavailable());
        service = new LazarusEnrichmentService(filings, marketData, altmanZ, companyData,
                new de.visterion.dracul.strigoi.echo.RevisionsProxy());
    }

    private static LazarusCandidate candidate(String symbol, double pctAboveLow) {
        return new LazarusCandidate(symbol, symbol + " Inc", 10.0, 9.0, 40.0, pctAboveLow,
                5.0, 1.8, 0.4, 35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3, 900.0);
    }

    /** Agora answered — with an error envelope about this one symbol. */
    private static AgoraUnavailableException perRequest(String detail) {
        return new AgoraUnavailableException(Scope.REQUEST, "Agora tool error: " + detail, null);
    }

    @Test
    void oneRequestScopedScoreFailureDoesNotDisableTheScoreSource() {
        when(filings.fundamentalScoreStrict("SYNA")).thenThrow(perRequest("no CIK for SYNA"));
        when(filings.fundamentalScoreStrict("SYNB")).thenReturn(GOOD_SCORE);

        EnrichedLazarusBatch batch =
                service.enrich(List.of(candidate("SYNA", 0.01), candidate("SYNB", 0.02)));

        verify(filings, times(2)).fundamentalScoreStrict(anyString()); // source NOT disabled
        assertThat(batch.candidates().get(0).fScore()).isZero();       // fail-soft for SYNA only
        assertThat(batch.candidates().get(1).fScore()).isEqualTo(7);
        assertThat(batch.degradedCandidates()).isEqualTo(1);           // the per-item loss is visible
    }

    @Test
    void oneRequestScopedConceptFailureDoesNotDisableTheConceptSource() {
        when(filings.fundamentalScoreStrict(anyString())).thenReturn(GOOD_SCORE);
        when(altmanZ.zScore(eq("SYNA"), any(), any())).thenThrow(perRequest("no CIK for SYNA"));
        when(altmanZ.zScore(eq("SYNB"), any(), any())).thenReturn(Z_OK);

        EnrichedLazarusBatch batch =
                service.enrich(List.of(candidate("SYNA", 0.01), candidate("SYNB", 0.02)));

        verify(altmanZ, times(1)).zScore(eq("SYNA"), any(), any());
        verify(altmanZ, times(1)).zScore(eq("SYNB"), any(), any());   // source NOT disabled
        assertThat(batch.candidates().get(0).zScoreAvailable()).isFalse();
        assertThat(batch.candidates().get(1).zScore()).isEqualByComparingTo("2.00");
        assertThat(batch.degradedCandidates()).isEqualTo(1);
    }

    @Test
    void threeConsecutiveRequestScopedScoreFailuresTripTheGuard() {
        when(filings.fundamentalScoreStrict(anyString())).thenThrow(perRequest("no CIK"));

        EnrichedLazarusBatch batch = service.enrich(List.of(
                candidate("SYNA", 0.01), candidate("SYNB", 0.02),
                candidate("SYNC", 0.03), candidate("SYND", 0.04)));

        // three in a row with no success in between IS a statement about the source
        verify(filings, times(3)).fundamentalScoreStrict(anyString());
        assertThat(batch.candidates()).hasSize(4);
        assertThat(batch.degradedCandidates()).isEqualTo(4); // the fourth lost it to the down source
    }

    @Test
    void threeConsecutiveRequestScopedConceptFailuresTripTheGuard() {
        when(filings.fundamentalScoreStrict(anyString())).thenReturn(GOOD_SCORE);
        when(altmanZ.zScore(anyString(), any(), any())).thenThrow(perRequest("no CIK"));

        service.enrich(List.of(candidate("SYNA", 0.01), candidate("SYNB", 0.02),
                candidate("SYNC", 0.03), candidate("SYND", 0.04)));

        verify(altmanZ, times(3)).zScore(anyString(), any(), any());
    }

    /** A success in between breaks the run, so the counter never reaches three. */
    @Test
    void aSuccessBetweenRequestFailuresKeepsTheScoreSourceUp() {
        when(filings.fundamentalScoreStrict("SYNA")).thenThrow(perRequest("no CIK for SYNA"));
        when(filings.fundamentalScoreStrict("SYNB")).thenReturn(GOOD_SCORE);
        when(filings.fundamentalScoreStrict("SYNC")).thenThrow(perRequest("no CIK for SYNC"));
        when(filings.fundamentalScoreStrict("SYND")).thenThrow(perRequest("no CIK for SYND"));

        EnrichedLazarusBatch batch = service.enrich(List.of(
                candidate("SYNA", 0.01), candidate("SYNB", 0.02),
                candidate("SYNC", 0.03), candidate("SYND", 0.04)));

        verify(filings, times(4)).fundamentalScoreStrict(anyString());
        assertThat(batch.degradedCandidates()).isEqualTo(3);
    }
}
