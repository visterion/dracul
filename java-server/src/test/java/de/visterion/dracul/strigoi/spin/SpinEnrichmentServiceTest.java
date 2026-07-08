package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.FilingText;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SpinEnrichmentServiceTest {

    private SpinCandidate candidate(String sym) {
        return new SpinCandidate(sym, sym + " SpinCo", "10-12B", "2026-05-20", "https://sec/" + sym);
    }

    @Test void threadsTermSheet() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        when(filings.filingText("https://sec/SPN"))
                .thenReturn(new FilingText("SUMMARY distribution ratio 1:3, record date ...", true));

        List<EnrichedSpinCandidate> out = new SpinEnrichmentService(filings).enrich(List.of(candidate("SPN")));

        assertThat(out).hasSize(1);
        EnrichedSpinCandidate e = out.get(0);
        assertThat(e.symbol()).isEqualTo("SPN");
        assertThat(e.termSheet()).contains("distribution ratio 1:3");
        assertThat(e.termSheetAvailable()).isTrue();
    }

    @Test void degradesWhenFilingUnavailable() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        when(filings.filingText(any())).thenReturn(FilingText.unavailable());

        List<EnrichedSpinCandidate> out = new SpinEnrichmentService(filings).enrich(List.of(candidate("ABC")));

        assertThat(out.get(0).termSheetAvailable()).isFalse();
        assertThat(out.get(0).termSheet()).isEmpty();
    }

    @Test void neverThrowsWhenFilingTextThrows() {
        AgoraFilings filings = Mockito.mock(AgoraFilings.class);
        when(filings.filingText(any())).thenThrow(new RuntimeException("boom"));

        List<EnrichedSpinCandidate> out = new SpinEnrichmentService(filings).enrich(List.of(candidate("XYZ")));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).termSheetAvailable()).isFalse();
    }
}
