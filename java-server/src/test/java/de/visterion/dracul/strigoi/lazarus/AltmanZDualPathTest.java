package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import de.visterion.dracul.hunting.agora.FundamentalConcept;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Guards the routing seam: US symbols hit {@code companyFactsStrict} and NEVER the concept
 *  path; venue-suffixed symbols hit {@code conceptsStrict} and NEVER {@code companyFactsStrict}. */
class AltmanZDualPathTest {

    private static final LocalDate BS_DATE = LocalDate.of(2025, 12, 31);

    private AgoraFilings filings;
    private AltmanZCalculator calculator;

    @BeforeEach
    void setUp() {
        filings = mock(AgoraFilings.class);
        calculator = new AltmanZCalculator(filings,
                new InstrumentClassifier(List.of("DE", "T", "HK")));
    }

    private static ConceptSeries.Point instant(long v) {
        return new ConceptSeries.Point(null, BS_DATE, BigDecimal.valueOf(v));
    }

    private static ConceptSeries.Point annual(long v) {
        return new ConceptSeries.Point(BS_DATE.minusDays(364), BS_DATE, BigDecimal.valueOf(v));
    }

    @Test void usSymbolUsesCompanyFactsAndNeverTouchesTheConceptPath() {
        Map<String, ConceptSeries> facts = new LinkedHashMap<>();
        facts.put("Assets", new ConceptSeries("Assets", List.of(instant(1_000_000_000L))));
        facts.put("AssetsCurrent", new ConceptSeries("AssetsCurrent", List.of(instant(400_000_000L))));
        facts.put("LiabilitiesCurrent", new ConceptSeries("LiabilitiesCurrent", List.of(instant(250_000_000L))));
        facts.put("Liabilities", new ConceptSeries("Liabilities", List.of(instant(600_000_000L))));
        facts.put("RetainedEarningsAccumulatedDeficit",
                new ConceptSeries("RetainedEarningsAccumulatedDeficit", List.of(instant(300_000_000L))));
        facts.put("OperatingIncomeLoss", new ConceptSeries("OperatingIncomeLoss", List.of(annual(120_000_000L))));
        facts.put("Revenues", new ConceptSeries("Revenues", List.of(annual(1_500_000_000L))));
        when(filings.companyFactsStrict(eq("ACME"), anyList())).thenAnswer(inv -> {
            List<String> tags = inv.getArgument(1);
            Map<String, ConceptSeries> out = new LinkedHashMap<>();
            for (String t : tags) out.put(t, facts.getOrDefault(t, ConceptSeries.empty(t)));
            return out;
        });

        AltmanZCalculator.AltmanZ z = calculator.zScore("ACME", 900.0, "USD");

        // byte-identical to the US golden hand calculation
        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("3.40");
        verify(filings, times(1)).companyFactsStrict(eq("ACME"), anyList());
        verify(filings, never()).conceptsStrict(any(), any(FundamentalConcept[].class));
    }

    @Test void nonUsSymbolUsesConceptPathAndNeverTouchesCompanyFacts() {
        when(filings.conceptsStrict(eq("SAP.DE"), any(FundamentalConcept[].class))).thenReturn(multi(
                600_000_000L, 900.0)); // liabilities 600M EUR, arbitrary healthy rest below

        calculator.zScore("SAP.DE", 900.0, "EUR");

        verify(filings, times(1)).conceptsStrict(eq("SAP.DE"), any(FundamentalConcept[].class));
        verify(filings, never()).companyFactsStrict(any(), anyList());
    }

    /** A healthy EUR concept batch (assets 1000M, working capital 150M, retained 300M,
     *  liabilities as given, EBIT 120M, revenue 1500M) — enough to route and compute. */
    private static ConceptSeries.MultiConcept multi(long liabilities, double ignoredCap) {
        Map<FundamentalConcept, ConceptSeries> s = new EnumMap<>(FundamentalConcept.class);
        Map<FundamentalConcept, String> u = new EnumMap<>(FundamentalConcept.class);
        s.put(FundamentalConcept.TOTAL_ASSETS, series("TOTAL_ASSETS", instant(1_000_000_000L)));
        s.put(FundamentalConcept.CURRENT_ASSETS, series("CURRENT_ASSETS", instant(400_000_000L)));
        s.put(FundamentalConcept.CURRENT_LIABILITIES, series("CURRENT_LIABILITIES", instant(250_000_000L)));
        s.put(FundamentalConcept.TOTAL_LIABILITIES, series("TOTAL_LIABILITIES", instant(liabilities)));
        s.put(FundamentalConcept.RETAINED_EARNINGS, series("RETAINED_EARNINGS", instant(300_000_000L)));
        s.put(FundamentalConcept.EBIT, series("EBIT", annual(120_000_000L)));
        s.put(FundamentalConcept.REVENUE, series("REVENUE", annual(1_500_000_000L)));
        for (FundamentalConcept c : FundamentalConcept.values()) u.put(c, "EUR");
        return new ConceptSeries.MultiConcept(s, u);
    }

    private static ConceptSeries series(String tag, ConceptSeries.Point p) {
        return new ConceptSeries(tag, List.of(p));
    }
}
