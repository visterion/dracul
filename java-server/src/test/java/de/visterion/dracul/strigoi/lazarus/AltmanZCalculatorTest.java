package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AltmanZCalculatorTest {

    private static final LocalDate BS_DATE = LocalDate.of(2025, 12, 31);      // balance-sheet date

    private AgoraFilings filings;
    private AltmanZCalculator calculator;
    /** Per-tag series stubbed by each test; the bulk mock fills unstubbed tags with an empty
     *  series, mirroring companyFactsStrict's "absent tag => empty, not missing" contract. */
    private Map<String, ConceptSeries> stubbed;

    @BeforeEach
    void setUp() {
        filings = mock(AgoraFilings.class);
        stubbed = new HashMap<>();
        // one bulk call; returns a series for EVERY requested tag (empty unless stubbed)
        when(filings.companyFactsStrict(eq("ACME"), anyList())).thenAnswer(inv -> {
            List<String> tags = inv.getArgument(1);
            Map<String, ConceptSeries> out = new LinkedHashMap<>();
            for (String tag : tags) out.put(tag, stubbed.getOrDefault(tag, ConceptSeries.empty(tag)));
            return out;
        });
        calculator = new AltmanZCalculator(filings);
    }

    private void stubInstant(String tag, long usd) { stubInstant(tag, BS_DATE, usd); }

    private void stubInstant(String tag, LocalDate end, long usd) {
        stubbed.put(tag, new ConceptSeries(tag,
                List.of(new ConceptSeries.Point(null, end, BigDecimal.valueOf(usd)))));
    }

    private void stubAnnual(String tag, long usd) { stubAnnual(tag, BS_DATE, usd); }

    /** One annual (364-day) duration point ending at {@code end}. */
    private void stubAnnual(String tag, LocalDate end, long usd) {
        stubbed.put(tag, new ConceptSeries(tag,
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

    /** The whole XBRL fan-out is now ONE bulk fetch — no per-tag remote call. */
    @Test
    void fetchesAllTagsInASingleBulkCall() {
        stubHealthyConcepts();

        calculator.zScore("ACME", 900.0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tags = ArgumentCaptor.forClass(List.class);
        verify(filings, times(1)).companyFactsStrict(eq("ACME"), tags.capture());
        verifyNoMoreInteractions(filings);
        // the request carries every balance-sheet, derivation, flow and revenue-fallback tag
        assertThat(tags.getValue()).contains(
                "Assets", "AssetsCurrent", "LiabilitiesCurrent", "Liabilities",
                "LiabilitiesAndStockholdersEquity", "StockholdersEquity",
                "RetainedEarningsAccumulatedDeficit", "OperatingIncomeLoss",
                "Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "SalesRevenueNet");
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
            stubbed.remove(missing);   // absent from the bulk map (comes back empty)

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
        stubbed.put("OperatingIncomeLoss", new ConceptSeries("OperatingIncomeLoss",
                List.of(new ConceptSeries.Point(BS_DATE.minusDays(90), BS_DATE, BigDecimal.valueOf(30_000_000L)))));

        assertThat(calculator.zScore("ACME", 900.0).available()).isFalse();
    }

    @Test
    void fallsBackThroughTheRevenueTagChain() {
        stubHealthyConcepts();
        stubbed.remove("Revenues");   // primary revenue tag not filed
        stubAnnual("RevenueFromContractWithCustomerExcludingAssessedTax", 1_500_000_000L);

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0);

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("3.40"); // same hand calculation as above
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
    }

    @Test
    void nonPositiveLiabilitiesYieldUnavailable() {
        stubHealthyConcepts();
        stubInstant("Liabilities", 0L); // X4 denominator; no derivation operands stubbed

        assertThat(calculator.zScore("ACME", 900.0).available()).isFalse();
    }

    @Test
    void anchorsAllInstantsToTheLatestAssetsDate() {
        stubHealthyConcepts();
        // Assets has an older AND the current instant; the latest one anchors the batch
        stubbed.put("Assets", new ConceptSeries("Assets", List.of(
                new ConceptSeries.Point(null, BS_DATE.minusYears(1), BigDecimal.valueOf(2_000_000_000L)),
                new ConceptSeries.Point(null, BS_DATE, BigDecimal.valueOf(1_000_000_000L)))));

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0);

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("3.40"); // computed off the 1000M balance sheet
    }

    // ---- Task B: Liabilities derivation --------------------------------------------------

    /** Filer omits the standalone {@code Liabilities} tag but reports
     *  {@code LiabilitiesAndStockholdersEquity} (== total assets, 1000M) and
     *  {@code StockholdersEquity} (400M): liabilities are derived as 1000M - 400M = 600M at the
     *  same anchor date, reproducing the healthy 600M and hence the pinned Z = 3.40. */
    @Test
    void derivesLiabilitiesFromIdentityWhenStandaloneTagAbsent() {
        stubHealthyConcepts();
        stubbed.remove("Liabilities");                                   // standalone tag absent
        stubInstant("LiabilitiesAndStockholdersEquity", 1_000_000_000L); // == total assets
        stubInstant("StockholdersEquity", 400_000_000L);

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0);

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("3.40"); // derived liabilities 600M
    }

    /** Derivation that yields a non-positive result (equity exceeds
     *  LiabilitiesAndStockholdersEquity) is discarded -> Z unavailable, never a bogus negative
     *  denominator. */
    @Test
    void nonPositiveDerivedLiabilitiesYieldUnavailable() {
        stubHealthyConcepts();
        stubbed.remove("Liabilities");
        stubInstant("LiabilitiesAndStockholdersEquity", 400_000_000L);
        stubInstant("StockholdersEquity", 500_000_000L);   // derived = -100M -> discarded

        assertThat(calculator.zScore("ACME", 900.0).available()).isFalse();
    }

    // ---- Task B: restatement (latest-filed) dedup ----------------------------------------

    /** Two Assets instants at the SAME period end but different {@code filed} dates (an original
     *  and a later restatement): the later-filed value (1000M) must win over the earlier one
     *  (2000M). Pinned via Z = 3.40 (the 1000M balance sheet); the 2000M sheet would give a
     *  clearly different Z (2.15). */
    @Test
    void restatedInstantPrefersTheLatestFiledValue() {
        stubHealthyConcepts();
        stubbed.put("Assets", new ConceptSeries("Assets", List.of(
                new ConceptSeries.Point(null, BS_DATE, BigDecimal.valueOf(2_000_000_000L), LocalDate.of(2026, 2, 1)),
                new ConceptSeries.Point(null, BS_DATE, BigDecimal.valueOf(1_000_000_000L), LocalDate.of(2026, 5, 1)))));

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0);

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("3.40"); // later-filed 1000M, not stale 2000M
    }

    /** A stale duplicate annual revenue point (earlier filed, 1400M) does not override the
     *  restated one (later filed, 1500M) for the same fiscal-year end. Pinned via Z = 3.40
     *  (1500M -> X5 = 1.5); the stale 1400M would give Z = 3.30. */
    @Test
    void restatedRevenuePrefersTheLatestFiledValue() {
        stubHealthyConcepts();
        stubbed.put("Revenues", new ConceptSeries("Revenues", List.of(
                new ConceptSeries.Point(BS_DATE.minusDays(364), BS_DATE,
                        BigDecimal.valueOf(1_400_000_000L), LocalDate.of(2026, 2, 1)),   // original
                new ConceptSeries.Point(BS_DATE.minusDays(364), BS_DATE,
                        BigDecimal.valueOf(1_500_000_000L), LocalDate.of(2026, 5, 1))))); // restatement

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0);

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("3.40"); // restated 1500M, not stale 1400M
    }

    /** Primary-key guard for {@code latestInstant}: a later-filed RESTATEMENT of an OLDER
     *  balance-sheet date must NOT displace the genuinely newer period. The newer period
     *  (BS_DATE, 1000M) carries an EARLIER filed date than the restated old period
     *  (BS_DATE-1y, 2000M, filed later). A naive "greatest filed overall" pick would anchor the
     *  batch on the stale year, mismatch every other instant (all at BS_DATE) and yield
     *  unavailable — so pinning Z = 3.40 (the current 1000M sheet) guards period-end-as-primary,
     *  not merely the equal-period tie-break already covered above. */
    @Test
    void latestInstantKeepsNewestPeriodOverALaterFiledRestatementOfAnOlderPeriod() {
        stubHealthyConcepts();
        stubbed.put("Assets", new ConceptSeries("Assets", List.of(
                new ConceptSeries.Point(null, BS_DATE.minusYears(1),
                        BigDecimal.valueOf(2_000_000_000L), LocalDate.of(2026, 6, 1)),   // restated OLD year, filed late
                new ConceptSeries.Point(null, BS_DATE,
                        BigDecimal.valueOf(1_000_000_000L), LocalDate.of(2025, 2, 1))))); // actual latest year, filed earlier

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0);

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("3.40"); // newest period, not the later-filed stale year
    }

    /** Same primary-key guard for the annual-flow picker {@code latestAnnualDuration} (EBIT): a
     *  later-filed restatement of an OLDER fiscal year must not beat the genuinely newer one. The
     *  newer FY (ending BS_DATE, 120M) is filed earlier than the restated old FY (ending
     *  BS_DATE-1y, filed later); a naive filed-first pick would set fyEnd to the stale year, the
     *  revenue chain would find no annual point at that end, and Z would go unavailable. Pinning
     *  Z = 3.40 guards period-end as latestAnnualDuration's primary key. */
    @Test
    void latestAnnualDurationKeepsNewestFiscalYearOverALaterFiledRestatementOfAnOlderYear() {
        stubHealthyConcepts();
        LocalDate oldFyEnd = BS_DATE.minusYears(1);
        stubbed.put("OperatingIncomeLoss", new ConceptSeries("OperatingIncomeLoss", List.of(
                new ConceptSeries.Point(oldFyEnd.minusDays(364), oldFyEnd,
                        BigDecimal.valueOf(999_000_000L), LocalDate.of(2026, 6, 1)),      // restated OLD FY, filed late
                new ConceptSeries.Point(BS_DATE.minusDays(364), BS_DATE,
                        BigDecimal.valueOf(120_000_000L), LocalDate.of(2025, 2, 1)))));   // actual latest FY, filed earlier

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0);

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("3.40"); // newest fiscal year, not the later-filed stale one
    }

    @Test
    void agoraOutagePropagatesForTheBatchGuard() {
        when(filings.companyFactsStrict(eq("ACME"), any()))
                .thenThrow(new AgoraUnavailableException("down"));

        assertThatThrownBy(() -> calculator.zScore("ACME", 900.0))
                .isInstanceOf(AgoraUnavailableException.class);
    }
}
