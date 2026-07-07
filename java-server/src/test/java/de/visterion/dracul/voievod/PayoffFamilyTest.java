package de.visterion.dracul.voievod;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayoffFamilyTest {

    @Test
    void driftTypesMapToDrift() {
        assertThat(PayoffFamily.of("PEAD")).isEqualTo(PayoffFamily.DRIFT);
        assertThat(PayoffFamily.of("QUALITY_52W_LOW")).isEqualTo(PayoffFamily.DRIFT);
        assertThat(PayoffFamily.of("INSIDER_CLUSTER")).isEqualTo(PayoffFamily.DRIFT);
        assertThat(PayoffFamily.of("SPINOFF")).isEqualTo(PayoffFamily.DRIFT);
    }

    @Test
    void eventTypesMapToEvent() {
        assertThat(PayoffFamily.of("MERGER_ARB")).isEqualTo(PayoffFamily.EVENT);
        assertThat(PayoffFamily.of("INDEX_INCLUSION")).isEqualTo(PayoffFamily.EVENT);
    }

    @Test
    void unknownNullBlankMapToUnknown() {
        assertThat(PayoffFamily.of("WHATEVER")).isEqualTo(PayoffFamily.UNKNOWN);
        assertThat(PayoffFamily.of(null)).isEqualTo(PayoffFamily.UNKNOWN);
        assertThat(PayoffFamily.of("")).isEqualTo(PayoffFamily.UNKNOWN);
        assertThat(PayoffFamily.of("  ")).isEqualTo(PayoffFamily.UNKNOWN);
    }

    @Test
    void mappingIsCaseSensitiveToTheExactEmittedStrings() {
        assertThat(PayoffFamily.of("pead")).isEqualTo(PayoffFamily.UNKNOWN);
    }
}
