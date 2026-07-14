package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.strigoi.echo.RevisionsProxy;
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

/** Task B3: the Altman-Z attempt is decoupled from the Piotroski F-score. Z is tried whenever
 *  the concept source is up ({@code !conceptsDown}) — no longer gated on {@code s.available()} —
 *  because non-US F-scores are often sparse while their concept balance sheets are present. */
class LazarusEnrichmentLatchTest {

    private static final AltmanZCalculator.AltmanZ Z_OK =
            new AltmanZCalculator.AltmanZ(new BigDecimal("2.00"), true);

    private AgoraFilings filings;
    private AltmanZCalculator altmanZ;
    private LazarusEnrichmentService service;

    @BeforeEach
    void setUp() {
        filings = mock(AgoraFilings.class);
        AgoraMarketData marketData = mock(AgoraMarketData.class);   // empty bars -> no timing
        altmanZ = mock(AltmanZCalculator.class);
        AgoraCompanyData companyData = mock(AgoraCompanyData.class); // empty trend -> no revisions
        service = new LazarusEnrichmentService(filings, marketData, altmanZ, companyData,
                new RevisionsProxy());
    }

    private static LazarusCandidate candidate(String symbol) {
        return new LazarusCandidate(symbol, symbol + " Inc", 10.0, 9.0, 40.0, 0.05,
                5.0, 1.8, 0.4, 35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3, 900.0);
    }

    @Test void zAttemptedEvenWhenFundamentalScoreUnavailable() {
        // F-score unavailable (available()==false) — a sparse non-US name — must NOT block Z
        when(filings.fundamentalScoreStrict("SPARSE")).thenReturn(FundamentalScore.unavailable());
        when(altmanZ.zScore(anyString(), any(), any())).thenReturn(Z_OK);

        EnrichedLazarusCandidate e = service.enrich(List.of(candidate("SPARSE"))).get(0);

        verify(altmanZ, times(1)).zScore(eq("SPARSE"), any(), any());
        assertThat(e.zScoreAvailable()).isTrue();
        assertThat(e.zScore()).isEqualByComparingTo("2.00");
        assertThat(e.fScore()).isZero(); // F-score still unavailable, only Z rode through
    }

    @Test void okEmptyConceptsMidBatchDoNotDisableZForLaterCandidates() {
        when(filings.fundamentalScoreStrict(anyString())).thenReturn(FundamentalScore.unavailable());
        // post-A2: a data-less symbol returns ok-empty -> zScore is unavailable() but does NOT throw
        when(altmanZ.zScore(eq("EMPTY"), any(), any()))
                .thenReturn(AltmanZCalculator.AltmanZ.unavailable());
        when(altmanZ.zScore(eq("LATER"), any(), any())).thenReturn(Z_OK);

        List<EnrichedLazarusCandidate> out =
                service.enrich(List.of(candidate("EMPTY"), candidate("LATER")));

        // an ok-empty (non-throwing) unavailable must not trip the source-down latch
        verify(altmanZ, times(1)).zScore(eq("EMPTY"), any(), any());
        verify(altmanZ, times(1)).zScore(eq("LATER"), any(), any());
        assertThat(out.get(0).zScoreAvailable()).isFalse();
        assertThat(out.get(1).zScoreAvailable()).isTrue();
    }

    @Test void exactlyOneConceptCallPerCandidate() {
        when(filings.fundamentalScoreStrict(anyString())).thenReturn(FundamentalScore.unavailable());
        when(altmanZ.zScore(anyString(), any(), any()))
                .thenReturn(AltmanZCalculator.AltmanZ.unavailable());

        service.enrich(List.of(candidate("A"), candidate("B"), candidate("C")));

        verify(altmanZ, times(1)).zScore(eq("A"), any(), any());
        verify(altmanZ, times(1)).zScore(eq("B"), any(), any());
        verify(altmanZ, times(1)).zScore(eq("C"), any(), any());
        verify(altmanZ, times(3)).zScore(anyString(), any(), any());
    }
}
