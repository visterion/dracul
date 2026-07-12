package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpinValuationSnapshotterTest {

    private static final LocalDate BS_DATE = LocalDate.of(2025, 12, 31);
    private static final String CIK = "0000123456";
    // Finnhub blob: pbAnnual, marketCapitalization (USD millions), pfcfShareTTM.
    private static final String FUND =
            "{\"pbAnnual\":2.5,\"marketCapitalization\":900,\"pfcfShareTTM\":20.0}";

    private final ObjectMapper mapper = new ObjectMapper();

    private AgoraFilings filings;
    private AgoraCompanyData companyData;
    private SpinValuationSnapshotter snapshotter;

    @BeforeEach
    void setUp() {
        filings = mock(AgoraFilings.class);
        companyData = mock(AgoraCompanyData.class);
        when(filings.conceptStrict(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> ConceptSeries.empty(inv.getArgument(2)));
        snapshotter = new SpinValuationSnapshotter(filings, companyData);
    }

    private void stubInstant(String tag, long usd) {
        when(filings.conceptStrict("SPN", CIK, tag)).thenReturn(new ConceptSeries(tag,
                List.of(new ConceptSeries.Point(null, BS_DATE, BigDecimal.valueOf(usd)))));
    }

    private void stubAnnual(String tag, long usd) {
        when(filings.conceptStrict("SPN", CIK, tag)).thenReturn(new ConceptSeries(tag,
                List.of(new ConceptSeries.Point(BS_DATE.minusDays(364), BS_DATE, BigDecimal.valueOf(usd)))));
    }

    private void stubBalanceSheetAndEbit() {
        stubInstant("Assets", 1_000_000_000L);
        stubInstant("Liabilities", 600_000_000L);      // bookValue = 400,000,000
        stubAnnual("OperatingIncomeLoss", 120_000_000L);
    }

    /** Hand calculation (no cash concept on file -> fail-soft EV without cash netting):
     *  bookValue    = 1000M - 600M                 = 400,000,000
     *  priceToBook  = pbAnnual                      = 2.5
     *  fcfYield     = 1 / pfcfShareTTM (1 / 20)      = 0.05
     *  EV           = 900M (mktcap USD) + 600M liab  = 1,500,000,000
     *  evToEbit     = 1500M / 120M                   = 12.5 */
    @Test void computesAllValuationRatiosAndBookValue() {
        stubBalanceSheetAndEbit();
        when(companyData.fundamentals("SPN")).thenReturn(mapper.readTree(FUND));

        var s = snapshotter.snapshot("SPN", CIK);

        assertThat(s.available()).isTrue();
        assertThat(s.priceToBook()).isEqualTo(2.5);
        assertThat(s.fcfYield()).isEqualTo(0.05);
        assertThat(s.evToEbit()).isEqualTo(12.5);
        assertThat(s.bookValue()).isEqualByComparingTo("400000000");
    }

    /** Cash is netted into EV when available, anchored to the balance-sheet date:
     *  EV = 900M + 600M liab - 300M cash = 1,200,000,000 -> evToEbit = 1200M / 120M = 10.0
     *  (lower than the 12.5 no-cash fallback above); book value is unaffected. */
    @Test void cashNettingLowersEnterpriseValue() {
        stubBalanceSheetAndEbit();
        stubInstant("CashAndCashEquivalentsAtCarryingValue", 300_000_000L);
        when(companyData.fundamentals("SPN")).thenReturn(mapper.readTree(FUND));

        var s = snapshotter.snapshot("SPN", CIK);

        assertThat(s.evToEbit()).isEqualTo(10.0);
        assertThat(s.bookValue()).isEqualByComparingTo("400000000");
    }

    @Test void fundamentalsUnavailableKeepsXbrlBookValueOnly() {
        stubBalanceSheetAndEbit();
        when(companyData.fundamentals("SPN")).thenReturn(null);   // swallowing lookup -> null blob

        var s = snapshotter.snapshot("SPN", CIK);

        assertThat(s.available()).isTrue();                       // bookValue survives
        assertThat(s.bookValue()).isEqualByComparingTo("400000000");
        assertThat(s.priceToBook()).isNull();
        assertThat(s.fcfYield()).isNull();
        assertThat(s.evToEbit()).isNull();                        // needs the Finnhub market cap
    }

    @Test void xbrlUnavailableKeepsFinnhubRatiosOnly() {
        // concepts empty (default); fundamentals present
        when(companyData.fundamentals("SPN")).thenReturn(mapper.readTree(FUND));

        var s = snapshotter.snapshot("SPN", CIK);

        assertThat(s.available()).isTrue();
        assertThat(s.priceToBook()).isEqualTo(2.5);
        assertThat(s.fcfYield()).isEqualTo(0.05);
        assertThat(s.bookValue()).isNull();
        assertThat(s.evToEbit()).isNull();                        // needs XBRL liabilities + EBIT
    }

    @Test void zeroEbitLeavesEvToEbitNull() {
        stubInstant("Assets", 1_000_000_000L);
        stubInstant("Liabilities", 600_000_000L);
        stubAnnual("OperatingIncomeLoss", 0L);                    // EBIT not positive
        when(companyData.fundamentals("SPN")).thenReturn(mapper.readTree(FUND));

        var s = snapshotter.snapshot("SPN", CIK);

        assertThat(s.evToEbit()).isNull();
        assertThat(s.bookValue()).isEqualByComparingTo("400000000");
        assertThat(s.priceToBook()).isEqualTo(2.5);
    }

    @Test void negativeEbitLeavesEvToEbitNull() {
        stubInstant("Assets", 1_000_000_000L);
        stubInstant("Liabilities", 600_000_000L);
        stubAnnual("OperatingIncomeLoss", -50_000_000L);          // loss-making -> EV/EBIT meaningless
        when(companyData.fundamentals("SPN")).thenReturn(mapper.readTree(FUND));

        var s = snapshotter.snapshot("SPN", CIK);

        assertThat(s.evToEbit()).isNull();
        assertThat(s.bookValue()).isEqualByComparingTo("400000000");
    }

    @Test void nonPositivePfcfLeavesFcfYieldNull() {
        stubBalanceSheetAndEbit();
        // negative FCF (pfcfShareTTM < 0): must map to null, never a negative "neutral-looking" yield
        when(companyData.fundamentals("SPN")).thenReturn(mapper.readTree(
                "{\"pbAnnual\":2.5,\"marketCapitalization\":900,\"pfcfShareTTM\":-20.0}"));

        var s = snapshotter.snapshot("SPN", CIK);

        assertThat(s.fcfYield()).isNull();
        assertThat(s.priceToBook()).isEqualTo(2.5);               // other ratios unaffected
        assertThat(s.evToEbit()).isEqualTo(12.5);
    }

    @Test void zeroPfcfLeavesFcfYieldNull() {
        stubBalanceSheetAndEbit();
        when(companyData.fundamentals("SPN")).thenReturn(mapper.readTree(
                "{\"pbAnnual\":2.5,\"pfcfShareTTM\":0}"));

        assertThat(snapshotter.snapshot("SPN", CIK).fcfYield()).isNull();
    }

    @Test void thinFundamentalsDegradeMissingKeysToNull() {
        stubBalanceSheetAndEbit();
        when(companyData.fundamentals("SPN"))
                .thenReturn(mapper.readTree("{\"pbAnnual\":2.5}"));   // no pfcf, no market cap

        var s = snapshotter.snapshot("SPN", CIK);

        assertThat(s.priceToBook()).isEqualTo(2.5);
        assertThat(s.fcfYield()).isNull();
        assertThat(s.evToEbit()).isNull();                        // no market cap
        assertThat(s.bookValue()).isEqualByComparingTo("400000000");
    }

    @Test void everySourceUnavailableYieldsAllNullNoThrow() {
        when(companyData.fundamentals("SPN")).thenReturn(null);   // concepts empty by default

        var s = snapshotter.snapshot("SPN", CIK);

        assertThat(s.available()).isFalse();
        assertThat(s.priceToBook()).isNull();
        assertThat(s.evToEbit()).isNull();
        assertThat(s.fcfYield()).isNull();
        assertThat(s.bookValue()).isNull();
    }

    @Test void conceptOutagePropagatesForTheBatchGuard() {
        when(filings.conceptStrict("SPN", CIK, "Assets"))
                .thenThrow(new AgoraUnavailableException("edgar down"));

        assertThatThrownBy(() -> snapshotter.snapshot("SPN", CIK))
                .isInstanceOf(AgoraUnavailableException.class);
    }
}
