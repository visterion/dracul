package de.visterion.dracul.strigoi.lazarus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AltmanZClassifyTest {

    /** The default whitelist (the same suffixes AltmanZCalculator ships with). */
    private final InstrumentClassifier classifier = new InstrumentClassifier(List.of(
            "DE", "MI", "TO", "L", "T", "HK", "PA", "AS", "SW", "AX",
            "ST", "CO", "OL", "HE", "MC", "BR", "LS", "VI", "IR", "NZ"));

    @Test void bareUsTickerIsUs() {
        assertThat(classifier.isNonUs("AAPL")).isFalse();
    }

    @Test void classShareTickersAreUsNotNonUs() {
        // suffix "B" is a share class, not a venue -> US
        assertThat(classifier.isNonUs("BRK.B")).isFalse();
        assertThat(classifier.isNonUs("BF.B")).isFalse();
    }

    @Test void venueSuffixedTickersAreNonUs() {
        assertThat(classifier.isNonUs("SAP.DE")).isTrue();
        assertThat(classifier.isNonUs("7203.T")).isTrue();
        assertThat(classifier.isNonUs("0700.HK")).isTrue();
    }

    @Test void suffixMatchIsCaseInsensitive() {
        assertThat(classifier.isNonUs("sap.de")).isTrue();
    }

    @Test void trailingDotHasNoSuffixAndIsUs() {
        assertThat(classifier.isNonUs("WEIRD.")).isFalse();
    }
}
