package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.Form4OwnerHistory;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.strigoi.echo.EquityMetrics;
import de.visterion.dracul.strigoi.echo.EquityMetricsExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpinDistributionSnapshotterTest {

    private static final LocalDate DIST = LocalDate.of(2026, 6, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);   // 30 days later

    private EquityMetricsExtractor equityMetrics;
    private AgoraFilings filings;
    private SpinDistributionSnapshotter snapshotter;

    @BeforeEach
    void setUp() {
        equityMetrics = mock(EquityMetricsExtractor.class);
        filings = mock(AgoraFilings.class);
        // default: metrics unavailable; no insider history
        when(equityMetrics.metrics(anyString())).thenReturn(EquityMetrics.unavailable());
        when(filings.ownerHistoryStrict(anyString())).thenReturn(history());
        snapshotter = new SpinDistributionSnapshotter(equityMetrics, filings);
    }

    private static EquityMetrics cap(double marketCapMillions) {
        return new EquityMetrics(null, marketCapMillions, null, null, null, true);
    }

    private static Form4OwnerHistory history(Form4OwnerHistory.Transaction... txs) {
        return new Form4OwnerHistory("0000123456", DIST.minusYears(1), TODAY,
                List.of(new Form4OwnerHistory.Owner("Jane Doe", "0001", "CEO", List.of(txs))), false);
    }

    private static Form4OwnerHistory.Transaction tx(LocalDate date, String code) {
        return new Form4OwnerHistory.Transaction(date, code, "A", "4",
                BigDecimal.valueOf(1000), BigDecimal.TEN, BigDecimal.valueOf(10000), null, null);
    }

    @Test void computesSizeRatioDaysAndPostSpinBuying() {
        when(equityMetrics.metrics("SPN")).thenReturn(cap(300.0));
        when(equityMetrics.metrics("PAR")).thenReturn(cap(1500.0));
        when(filings.ownerHistoryStrict("SPN"))
                .thenReturn(history(tx(LocalDate.of(2026, 6, 15), "P")));   // open-market buy after DIST

        var s = snapshotter.snapshot("SPN", "PAR", DIST, TODAY);

        assertThat(s.spincoMarketCapMillions()).isEqualTo(300.0);
        assertThat(s.parentMarketCapMillions()).isEqualTo(1500.0);
        assertThat(s.sizeRatio()).isEqualTo(0.2);                 // 300 / 1500
        assertThat(s.daysSinceDistribution()).isEqualTo(30);
        assertThat(s.postSpinInsiderBuying()).isTrue();
        assertThat(s.marketCapAvailable()).isTrue();
        assertThat(s.insiderAvailable()).isTrue();
    }

    @Test void blankParentLeavesParentFieldsAndRatioNull() {
        when(equityMetrics.metrics("SPN")).thenReturn(cap(300.0));

        var s = snapshotter.snapshot("SPN", "", DIST, TODAY);

        assertThat(s.spincoMarketCapMillions()).isEqualTo(300.0);
        assertThat(s.parentMarketCapMillions()).isNull();
        assertThat(s.sizeRatio()).isNull();
        assertThat(s.marketCapAvailable()).isTrue();
        verify(equityMetrics, never()).metrics("");               // blank parent never looked up
    }

    @Test void marketCapSourceUnavailableDegradesWithoutThrow() {
        // metrics default = unavailable; insider still resolves
        when(filings.ownerHistoryStrict("SPN"))
                .thenReturn(history(tx(LocalDate.of(2026, 6, 15), "P")));

        var s = snapshotter.snapshot("SPN", "PAR", DIST, TODAY);

        assertThat(s.spincoMarketCapMillions()).isNull();
        assertThat(s.parentMarketCapMillions()).isNull();
        assertThat(s.sizeRatio()).isNull();
        assertThat(s.marketCapAvailable()).isFalse();
        assertThat(s.postSpinInsiderBuying()).isTrue();           // independent source still available
        assertThat(s.insiderAvailable()).isTrue();
    }

    @Test void onlyPreDistributionOrNonPurchaseActivityIsNotPostSpinBuying() {
        when(equityMetrics.metrics("SPN")).thenReturn(cap(300.0));
        when(filings.ownerHistoryStrict("SPN")).thenReturn(history(
                tx(LocalDate.of(2026, 5, 20), "P"),               // purchase BEFORE distribution
                tx(LocalDate.of(2026, 6, 20), "S")));             // post-distribution SALE, not a buy

        var s = snapshotter.snapshot("SPN", "PAR", DIST, TODAY);

        assertThat(s.postSpinInsiderBuying()).isFalse();
        assertThat(s.insiderAvailable()).isTrue();
    }

    @Test void nullDistributionDateDegradesCalendarAndInsiderFields() {
        when(equityMetrics.metrics("SPN")).thenReturn(cap(300.0));

        var s = snapshotter.snapshot("SPN", "PAR", null, TODAY);

        assertThat(s.spincoMarketCapMillions()).isEqualTo(300.0); // market cap unaffected
        assertThat(s.daysSinceDistribution()).isNull();
        assertThat(s.postSpinInsiderBuying()).isNull();
        assertThat(s.insiderAvailable()).isFalse();
        verify(filings, never()).ownerHistoryStrict(anyString()); // no distribution date -> no insider call
    }

    @Test void ownerHistoryOutagePropagatesForTheBatchGuard() {
        when(equityMetrics.metrics("SPN")).thenReturn(cap(300.0));
        when(filings.ownerHistoryStrict("SPN")).thenThrow(new AgoraUnavailableException("down"));

        assertThatThrownBy(() -> snapshotter.snapshot("SPN", "PAR", DIST, TODAY))
                .isInstanceOf(AgoraUnavailableException.class);
    }
}
