package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AltmanZCalculatorTest {

    private static final LocalDate BS_DATE = LocalDate.of(2025, 12, 31);      // balance-sheet date

    private AgoraFilings filings;
    private AltmanZCalculator calculator;

    @BeforeEach
    void setUp() {
        filings = mock(AgoraFilings.class);
        // default: every concept unknown/empty; individual tests stub what they need
        when(filings.conceptStrict(anyString(), anyString()))
                .thenAnswer(inv -> ConceptSeries.empty(inv.getArgument(1)));
        calculator = new AltmanZCalculator(filings);
    }

    private void stubInstant(String tag, long usd) { stubInstant(tag, BS_DATE, usd); }

    private void stubInstant(String tag, LocalDate end, long usd) {
        when(filings.conceptStrict("ACME", tag)).thenReturn(new ConceptSeries(tag,
                List.of(new ConceptSeries.Point(null, end, BigDecimal.valueOf(usd)))));
    }

    private void stubAnnual(String tag, long usd) { stubAnnual(tag, BS_DATE, usd); }

    /** One annual (364-day) duration point ending at {@code end}. */
    private void stubAnnual(String tag, LocalDate end, long usd) {
        when(filings.conceptStrict("ACME", tag)).thenReturn(new ConceptSeries(tag,
                List.of(new ConceptSeries.Point(end.minusDays(364), end, BigDecimal.valueOf(usd)))));
    }

    /** All seven XBRL inputs of a hand-checkable healthy filer (values in raw USD). */
    private void stubHealthyConcepts() {
        stubInstant("Assets", 1_000_000_000L);
        stubInstant("AssetsCurrent", 400_000_000L);
        stubInstant("LiabilitiesCurrent", 250_000_000L);
        stubInstant("Liabilities", 600_000_000L);
        stubInstant("RetainedEarningsAccumulatedDeficit", 300_000_000L);
        stubAnnual("OperatingIncomeLoss", 120_000_000L);
        stubAnnual("Revenues", 1_500_000_000L);
    }

    /** Hand calculation (all XBRL values raw USD, market cap 900 = USD millions):
     *  X1 = (400M - 250M) / 1000M          = 0.15   -> 1.2 * 0.15  = 0.18
     *  X2 = 300M / 1000M                   = 0.30   -> 1.4 * 0.30  = 0.42
     *  X3 = 120M / 1000M                   = 0.12   -> 3.3 * 0.12  = 0.396
     *  X4 = (900 * 10^6) / 600M            = 1.50   -> 0.6 * 1.50  = 0.90
     *  X5 = 1500M / 1000M                  = 1.50   -> 1.0 * 1.50  = 1.50
     *  Z  = 0.18 + 0.42 + 0.396 + 0.90 + 1.50 = 3.396 -> 3.40 (scale 2). */
    @Test
    void computesClassicZFromHandCheckableInputs() {
        stubHealthyConcepts();

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0);

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("3.40");
    }

    /** Guards exactly the 10^6 units trap: Finnhub market cap is USD MILLIONS, XBRL raw USD.
     *  Every ratio except X4 is zeroed (WC 0, retained 0, EBIT 0, revenue 0), so with market
     *  cap 600 (millions) over 600,000,000 USD liabilities X4 = 1.0 and Z = 0.6 * 1.0 = 0.60
     *  exactly. An implementation that forgot the conversion would get X4 = 600 / 6*10^8 =
     *  10^-6 and Z = 0.00. */
    @Test
    void convertsMarketCapMillionsToUsdForX4() {
        stubInstant("Assets", 1_000_000_000L);
        stubInstant("AssetsCurrent", 250_000_000L);
        stubInstant("LiabilitiesCurrent", 250_000_000L);   // working capital 0 -> X1 = 0
        stubInstant("Liabilities", 600_000_000L);
        stubInstant("RetainedEarningsAccumulatedDeficit", 0L);
        stubAnnual("OperatingIncomeLoss", 0L);
        stubAnnual("Revenues", 0L);

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 600.0);

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("0.60");
    }

    /** Realistic distressed filer lands deep in the Z < 1.8 distress zone:
     *  X1 = (300M - 500M) / 2000M = -0.10; X2 = -400M / 2000M = -0.20;
     *  X3 = -50M / 2000M = -0.025; X4 = 270M / 1800M = 0.15; X5 = 1000M / 2000M = 0.50;
     *  Z = -0.12 - 0.28 - 0.0825 + 0.09 + 0.50 = 0.1075 -> 0.11. */
    @Test
    void distressedBalanceSheetScoresBelowDistressThreshold() {
        stubInstant("Assets", 2_000_000_000L);
        stubInstant("AssetsCurrent", 300_000_000L);
        stubInstant("LiabilitiesCurrent", 500_000_000L);
        stubInstant("Liabilities", 1_800_000_000L);
        stubInstant("RetainedEarningsAccumulatedDeficit", -400_000_000L);
        stubAnnual("OperatingIncomeLoss", -50_000_000L);
        stubAnnual("Revenues", 1_000_000_000L);

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 270.0);

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("0.11");
    }

    @Test
    void eachMissingXbrlInputYieldsUnavailable() {
        String[] tags = {"Assets", "AssetsCurrent", "LiabilitiesCurrent", "Liabilities",
                "RetainedEarningsAccumulatedDeficit", "OperatingIncomeLoss", "Revenues"};
        for (String missing : tags) {
            setUp(); // fresh mocks per case
            stubHealthyConcepts();
            when(filings.conceptStrict("ACME", missing)).thenReturn(ConceptSeries.empty(missing));

            AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0);

            assertThat(z.available()).as("missing %s must make Z unavailable", missing).isFalse();
            assertThat(z.zScore()).as("no partial Z with missing %s", missing).isNull();
        }
    }

    @Test
    void missingMarketCapYieldsUnavailableWithoutAnyRemoteCall() {
        assertThat(calculator.zScore("ACME", null).available()).isFalse();
        assertThat(calculator.zScore("ACME", 0.0).available()).isFalse();
        assertThat(calculator.zScore("ACME", -5.0).available()).isFalse();
        verifyNoInteractions(filings);
    }

    @Test
    void stopsFetchingConceptsAfterTheFirstMissingInput() {
        // Assets (the first fetch) is empty -> no further concept calls are spent
        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0);

        assertThat(z.available()).isFalse();
        verify(filings, times(1)).conceptStrict(anyString(), anyString());
        verify(filings).conceptStrict("ACME", "Assets");
    }

    @Test
    void balanceSheetDateMismatchYieldsUnavailable() {
        stubHealthyConcepts();
        // filer stopped reporting Liabilities: latest instant is a year older than Assets'
        stubInstant("Liabilities", BS_DATE.minusYears(1), 600_000_000L);

        assertThat(calculator.zScore("ACME", 900.0).available()).isFalse();
    }

    @Test
    void flowFiscalYearMismatchYieldsUnavailable() {
        stubHealthyConcepts();
        // revenue only reported for the PRIOR fiscal year -> would mix flow periods in X3/X5
        stubAnnual("Revenues", BS_DATE.minusYears(1), 1_400_000_000L);

        assertThat(calculator.zScore("ACME", 900.0).available()).isFalse();
    }

    @Test
    void quarterlyDurationsDoNotCountAsAnnualFlows() {
        stubHealthyConcepts();
        // only a ~90-day EBIT duration on file -> not an annual flow, Z unavailable
        when(filings.conceptStrict("ACME", "OperatingIncomeLoss")).thenReturn(
                new ConceptSeries("OperatingIncomeLoss", List.of(new ConceptSeries.Point(
                        BS_DATE.minusDays(90), BS_DATE, BigDecimal.valueOf(30_000_000L)))));

        assertThat(calculator.zScore("ACME", 900.0).available()).isFalse();
    }

    @Test
    void fallsBackThroughTheRevenueTagChain() {
        stubHealthyConcepts();
        when(filings.conceptStrict("ACME", "Revenues")).thenReturn(ConceptSeries.empty("Revenues"));
        stubAnnual("RevenueFromContractWithCustomerExcludingAssessedTax", 1_500_000_000L);

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0);

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("3.40"); // same hand calculation as above
        verify(filings).conceptStrict("ACME", "RevenueFromContractWithCustomerExcludingAssessedTax");
        verify(filings, never()).conceptStrict("ACME", "SalesRevenueNet"); // chain stops on first hit
    }

    /** The ASC-606 tag-switcher case: EDGAR keeps historical datapoints forever, so the old
     *  {@code Revenues} tag still has annual points — but only up to the switch year. A tag
     *  only counts as a chain hit if it covers the EBIT fiscal-year end; the stale tag must
     *  fall through to the currently filed one instead of poisoning the Z permanently. */
    @Test
    void staleRevenueTagFallsThroughToTheCurrentlyFiledTag() {
        stubHealthyConcepts();
        // old tag: only a pre-switch fiscal year on file (ends a year before the EBIT FY end)
        stubAnnual("Revenues", BS_DATE.minusYears(1), 1_200_000_000L);
        // new tag: the annual point matching the EBIT fiscal-year end
        stubAnnual("RevenueFromContractWithCustomerExcludingAssessedTax", 1_500_000_000L);

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0);

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("3.40"); // computed off the NEW tag's 1500M
        verify(filings).conceptStrict("ACME", "Revenues");
        verify(filings).conceptStrict("ACME", "RevenueFromContractWithCustomerExcludingAssessedTax");
    }

    @Test
    void nonPositiveLiabilitiesYieldUnavailable() {
        stubHealthyConcepts();
        stubInstant("Liabilities", 0L); // X4 denominator

        assertThat(calculator.zScore("ACME", 900.0).available()).isFalse();
    }

    @Test
    void anchorsAllInstantsToTheLatestAssetsDate() {
        stubHealthyConcepts();
        // Assets has an older AND the current instant; the latest one anchors the batch
        when(filings.conceptStrict("ACME", "Assets")).thenReturn(new ConceptSeries("Assets", List.of(
                new ConceptSeries.Point(null, BS_DATE.minusYears(1), BigDecimal.valueOf(2_000_000_000L)),
                new ConceptSeries.Point(null, BS_DATE, BigDecimal.valueOf(1_000_000_000L)))));

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0);

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("3.40"); // computed off the 1000M balance sheet
    }

    @Test
    void agoraOutagePropagatesForTheBatchGuard() {
        when(filings.conceptStrict(eq("ACME"), anyString()))
                .thenThrow(new AgoraUnavailableException("down"));

        assertThatThrownBy(() -> calculator.zScore("ACME", 900.0))
                .isInstanceOf(AgoraUnavailableException.class);
    }
}
