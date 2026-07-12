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

class SpinBalanceSheetSnapshotterTest {

    private static final LocalDate BS_DATE = LocalDate.of(2025, 12, 31);
    private static final String CIK = "0000123456";

    private final ObjectMapper mapper = new ObjectMapper();

    private AgoraFilings filings;
    private AgoraCompanyData companyData;
    private SpinBalanceSheetSnapshotter snapshotter;

    @BeforeEach
    void setUp() {
        filings = mock(AgoraFilings.class);
        companyData = mock(AgoraCompanyData.class);
        // default: every concept empty (individual tests stub the tags they need)
        when(filings.conceptStrict(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> ConceptSeries.empty(inv.getArgument(2)));
        snapshotter = new SpinBalanceSheetSnapshotter(filings, companyData);
    }

    /** Stub one instant concept for the (symbol, CIK) pair the snapshot() call will forward. */
    private void stubInstant(String symbol, String tag, LocalDate end, long usd) {
        when(filings.conceptStrict(symbol, CIK, tag)).thenReturn(new ConceptSeries(tag,
                List.of(new ConceptSeries.Point(null, end, BigDecimal.valueOf(usd)))));
    }

    @Test void extractsAnchoredBalanceSheetAndIndustry() {
        stubInstant("SPN", "Assets", BS_DATE, 1_000_000_000L);
        stubInstant("SPN", "Liabilities", BS_DATE, 600_000_000L);
        stubInstant("SPN", "RetainedEarningsAccumulatedDeficit", BS_DATE, 300_000_000L);
        when(companyData.profile("SPN"))
                .thenReturn(mapper.readTree("{\"finnhubIndustry\":\"Industrials\"}"));

        var s = snapshotter.snapshot("SPN", CIK);

        assertThat(s.available()).isTrue();
        assertThat(s.totalAssets()).isEqualByComparingTo("1000000000");
        assertThat(s.totalLiabilities()).isEqualByComparingTo("600000000");
        assertThat(s.retainedEarnings()).isEqualByComparingTo("300000000");
        assertThat(s.industry()).isEqualTo("Industrials");
    }

    @Test void fetchesByCikWithoutASymbol() {
        // no ticker yet (REGISTERED stage): balance sheet still resolves, industry cannot be looked up
        stubInstant("", "Assets", BS_DATE, 1_000_000_000L);
        stubInstant("", "Liabilities", BS_DATE, 600_000_000L);
        stubInstant("", "RetainedEarningsAccumulatedDeficit", BS_DATE, 300_000_000L);

        var s = snapshotter.snapshot("", CIK);

        assertThat(s.available()).isTrue();
        assertThat(s.totalAssets()).isEqualByComparingTo("1000000000");
        assertThat(s.industry()).isNull();
    }

    @Test void missingAssetsMakesSnapshotUnavailableWithoutAnchoringOthers() {
        // Assets empty (default); the others are never anchored -> all null, unavailable
        stubInstant("SPN", "Liabilities", BS_DATE, 600_000_000L);
        stubInstant("SPN", "RetainedEarningsAccumulatedDeficit", BS_DATE, 300_000_000L);

        var s = snapshotter.snapshot("SPN", CIK);

        assertThat(s.available()).isFalse();
        assertThat(s.totalAssets()).isNull();
        assertThat(s.totalLiabilities()).isNull();
        assertThat(s.retainedEarnings()).isNull();
    }

    @Test void mismatchedDatesDegradeIndividualFieldsToNull() {
        stubInstant("SPN", "Assets", BS_DATE, 1_000_000_000L);
        // liabilities reported a year earlier -> not anchored to the Assets date -> null
        stubInstant("SPN", "Liabilities", BS_DATE.minusYears(1), 600_000_000L);
        stubInstant("SPN", "RetainedEarningsAccumulatedDeficit", BS_DATE, 300_000_000L);

        var s = snapshotter.snapshot("SPN", CIK);

        assertThat(s.available()).isTrue();                 // assets present
        assertThat(s.totalAssets()).isEqualByComparingTo("1000000000");
        assertThat(s.totalLiabilities()).isNull();          // date mismatch -> dropped
        assertThat(s.retainedEarnings()).isEqualByComparingTo("300000000");
    }

    @Test void profileSourceUnavailableDegradesIndustryOnly() {
        stubInstant("SPN", "Assets", BS_DATE, 1_000_000_000L);
        when(companyData.profile("SPN")).thenReturn(null);   // swallowing lookup returns null

        var s = snapshotter.snapshot("SPN", CIK);

        assertThat(s.available()).isTrue();
        assertThat(s.totalAssets()).isEqualByComparingTo("1000000000");
        assertThat(s.industry()).isNull();
    }

    @Test void everySourceUnavailableYieldsAllNullNoThrow() {
        var s = snapshotter.snapshot("SPN", CIK);           // all concepts empty, no profile stub -> null

        assertThat(s.available()).isFalse();
        assertThat(s.totalAssets()).isNull();
        assertThat(s.totalLiabilities()).isNull();
        assertThat(s.retainedEarnings()).isNull();
        assertThat(s.industry()).isNull();
    }

    @Test void conceptOutagePropagatesForTheBatchGuard() {
        when(filings.conceptStrict("SPN", CIK, "Assets"))
                .thenThrow(new AgoraUnavailableException("edgar down"));

        assertThatThrownBy(() -> snapshotter.snapshot("SPN", CIK))
                .isInstanceOf(AgoraUnavailableException.class);
    }
}
