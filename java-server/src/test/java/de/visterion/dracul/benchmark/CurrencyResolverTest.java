package de.visterion.dracul.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CurrencyResolverTest {

    @ParameterizedTest
    @CsvSource({
            "SYN1,USD", "syn1,USD", "SYN-B,USD",
            "SYN2.DE,EUR", "SYN2.F,EUR", "SYN2.AS,EUR", "SYN2.PA,EUR", "SYN2.MI,EUR",
            "SYN2.MC,EUR", "SYN2.BR,EUR", "SYN2.VI,EUR", "SYN2.HE,EUR", "SYN2.LS,EUR",
            "SYN1.HK,HKD", "SYN3.HK,HKD"})
    void knownSuffixesResolve(String symbol, String currency) {
        assertThat(CurrencyResolver.currencyOf(symbol)).contains(currency);
    }

    /** .L is deliberately unknown (plan ruling R1): no real .L bar was verified. */
    @ParameterizedTest
    @ValueSource(strings = {"SYN4.L", "SYN5.TO", "SYN6.XX", "USDEUR=X", "", "  "})
    void unknownSuffixesAreEmpty(String symbol) {
        assertThat(CurrencyResolver.currencyOf(symbol)).isEmpty();
    }

    @Test
    void nullIsEmpty() {
        assertThat(CurrencyResolver.currencyOf(null)).isEmpty();
    }

    @Test
    void fxPairUsesTheEurCrossNotation() {
        assertThat(CurrencyResolver.fxPair("USD")).isEqualTo("USDEUR=X");
        assertThat(CurrencyResolver.fxPair("HKD")).isEqualTo("HKDEUR=X");
    }
}
