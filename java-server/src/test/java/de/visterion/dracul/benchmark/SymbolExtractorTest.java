package de.visterion.dracul.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolExtractorTest {

    @Test
    void symbolFieldWinsAndIsNormalised() {
        assertThat(SymbolExtractor.extract(" syna ", "Synthetic Corp (SYNB)")).contains("SYNA");
    }

    @Test
    void blankSymbolFallsBackToTheFirstTickerOfTheFirstParenthesis() {
        assertThat(SymbolExtractor.extract("", "Synthetic Storage Trust  (SYNA, SYNA-PA, SYNA-PB)"))
                .contains("SYNA");
        assertThat(SymbolExtractor.extract(null, "Synthetic Corp (SYNA, SYNA-PA)")).contains("SYNA");
        assertThat(SymbolExtractor.extract("  ", "Synthetic Inc (SYNB)  (CIK 0000000001)")).contains("SYNB");
        assertThat(SymbolExtractor.extract(null, "Synthetic Class B (SYND.B)")).contains("SYND.B");
    }

    @Test
    void aCikGroupIsNotATicker() {
        assertThat(SymbolExtractor.extract(null, "Synthetic Holdings  (CIK 0000000001)")).isEmpty();
    }

    @Test
    void onlyTheFirstParenthesisCounts() {
        assertThat(SymbolExtractor.extract(null, "Synthetic (Delaware) Corp (SYNC)")).isEmpty();
    }

    @Test
    void nothingUsableIsEmpty() {
        assertThat(SymbolExtractor.extract(null, null)).isEmpty();
        assertThat(SymbolExtractor.extract("", "")).isEmpty();
        assertThat(SymbolExtractor.extract(null, "Synthetic Corp")).isEmpty();
        assertThat(SymbolExtractor.extract(null, "Synthetic (syna)")).isEmpty();
    }
}
