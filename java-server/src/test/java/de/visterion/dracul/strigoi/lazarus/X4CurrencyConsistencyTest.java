package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import de.visterion.dracul.hunting.agora.FundamentalConcept;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The non-US analogue of the US {@code convertsMarketCapMillionsToUsdForX4} guard: the cap is in
 *  reporting-currency MILLIONS and liabilities in raw reporting currency, so X4 = cap*10^6 /
 *  liabilities. With every other ratio zeroed and a EUR cap of 600 (millions) over 600,000,000 EUR
 *  liabilities, X4 = 1.0 and Z = 0.6 * 1.0 = 0.60 exactly. A forgotten *10^6 would give ~0.00. */
class X4CurrencyConsistencyTest {

    private static final LocalDate BS = LocalDate.of(2025, 12, 31);

    private static ConceptSeries instant(String tag, long v) {
        return new ConceptSeries(tag, List.of(new ConceptSeries.Point(null, BS, BigDecimal.valueOf(v))));
    }

    private static ConceptSeries annual(String tag, long v) {
        return new ConceptSeries(tag, List.of(
                new ConceptSeries.Point(BS.minusDays(364), BS, BigDecimal.valueOf(v))));
    }

    @Test void eurCapMillionsOverEurLiabilitiesGivesExpectedX4() {
        Map<FundamentalConcept, ConceptSeries> s = new EnumMap<>(FundamentalConcept.class);
        Map<FundamentalConcept, String> u = new EnumMap<>(FundamentalConcept.class);
        s.put(FundamentalConcept.TOTAL_ASSETS, instant("TOTAL_ASSETS", 1_000_000_000L));
        s.put(FundamentalConcept.CURRENT_ASSETS, instant("CURRENT_ASSETS", 250_000_000L));       // WC 0
        s.put(FundamentalConcept.CURRENT_LIABILITIES, instant("CURRENT_LIABILITIES", 250_000_000L));
        s.put(FundamentalConcept.TOTAL_LIABILITIES, instant("TOTAL_LIABILITIES", 600_000_000L));  // X4 denom
        s.put(FundamentalConcept.RETAINED_EARNINGS, instant("RETAINED_EARNINGS", 0L));            // X2 0
        s.put(FundamentalConcept.EBIT, annual("EBIT", 0L));                                       // X3 0
        s.put(FundamentalConcept.REVENUE, annual("REVENUE", 0L));                                 // X5 0
        for (FundamentalConcept c : FundamentalConcept.values()) u.put(c, "EUR");

        AgoraFilings filings = mock(AgoraFilings.class);
        when(filings.conceptsStrict(eq("EUR.DE"), any(FundamentalConcept[].class)))
                .thenReturn(new ConceptSeries.MultiConcept(s, u));
        AltmanZCalculator calc = new AltmanZCalculator(filings,
                new InstrumentClassifier(List.of("DE")));

        AltmanZCalculator.AltmanZ z = calc.zScore("EUR.DE", 600.0, "EUR");

        assertThat(z.available()).isTrue();
        assertThat(z.zScore()).isEqualByComparingTo("0.60");
    }
}
