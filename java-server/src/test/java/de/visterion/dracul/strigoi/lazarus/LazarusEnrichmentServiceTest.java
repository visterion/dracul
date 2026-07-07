package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LazarusEnrichmentServiceTest {

    private AgoraFilings filings;
    private LazarusEnrichmentService service;

    @BeforeEach
    void setUp() {
        filings = mock(AgoraFilings.class);
        service = new LazarusEnrichmentService(filings);
    }

    private static LazarusCandidate candidate(String symbol) {
        return new LazarusCandidate(symbol, symbol + " Inc", 10.0, 9.0, 40.0, 0.05,
                5.0, 1.8, 0.4, 35.0, 8.0, 4.0, 3.0, 1.2, 11.0, 2.3);
    }

    @Test
    void mapsFundamentalScoreOntoCandidate() {
        when(filings.fundamentalScore("ACME")).thenReturn(new FundamentalScore(
                7, 8, BigDecimal.valueOf(0.05), true, true, true));

        List<EnrichedLazarusCandidate> out = service.enrich(List.of(candidate("ACME")));

        assertThat(out).hasSize(1);
        EnrichedLazarusCandidate e = out.get(0);
        assertThat(e.symbol()).isEqualTo("ACME");
        assertThat(e.fScore()).isEqualTo(7);
        assertThat(e.fScoreCriteriaAvailable()).isEqualTo(8);
        assertThat(e.accrualRatio()).isEqualByComparingTo(BigDecimal.valueOf(0.05));
        assertThat(e.cfoExceedsNetIncome()).isTrue();
    }

    @Test
    void dropsCandidateWhenAccrualsGateFails() {
        when(filings.fundamentalScore("BADCO")).thenReturn(new FundamentalScore(
                3, 8, BigDecimal.valueOf(0.15), false, true, true));

        List<EnrichedLazarusCandidate> out = service.enrich(List.of(candidate("BADCO")));

        assertThat(out).isEmpty();
    }

    @Test
    void keepsCandidateWhenAccrualsSignalUnavailable() {
        when(filings.fundamentalScore("NOACCR")).thenReturn(new FundamentalScore(
                5, 6, null, false, false, true));

        List<EnrichedLazarusCandidate> out = service.enrich(List.of(candidate("NOACCR")));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).symbol()).isEqualTo("NOACCR");
    }

    @Test
    void keepsCandidateWithZeroScoreWhenFundamentalScoreUnavailable() {
        when(filings.fundamentalScore("NODATA")).thenReturn(FundamentalScore.unavailable());

        List<EnrichedLazarusCandidate> out = service.enrich(List.of(candidate("NODATA")));

        assertThat(out).hasSize(1);
        EnrichedLazarusCandidate e = out.get(0);
        assertThat(e.fScore()).isEqualTo(0);
        assertThat(e.fScoreCriteriaAvailable()).isEqualTo(0);
    }
}
