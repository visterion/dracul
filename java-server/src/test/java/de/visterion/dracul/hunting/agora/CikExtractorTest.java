package de.visterion.dracul.hunting.agora;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CikExtractorTest {

    @Test void parsesCanonicalArchiveUrlZeroPaddedTo10Digits() {
        assertThat(CikExtractor.fromFilingUrl(
                "https://www.sec.gov/Archives/edgar/data/320193/000032019324000123/aapl.htm"))
                .isEqualTo("0000320193");
    }

    @Test void stripsLeadingZerosThenRepadsWhenUrlAlreadyPadded() {
        assertThat(CikExtractor.fromFilingUrl(
                "https://www.sec.gov/Archives/edgar/data/0000320193/000032019324000123/aapl.htm"))
                .isEqualTo("0000320193");
    }

    @Test void handlesTrailingCikSegmentWithoutDocument() {
        assertThat(CikExtractor.fromFilingUrl("https://www.sec.gov/Archives/edgar/data/123456"))
                .isEqualTo("0000123456");
        assertThat(CikExtractor.fromFilingUrl("https://www.sec.gov/Archives/edgar/data/123456/"))
                .isEqualTo("0000123456");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://sec/u1",                                   // no archive path
            "https://www.sec.gov/cgi-bin/browse-edgar?CIK=320193",   // query-style, not /data/
            "https://www.sec.gov/Archives/edgar/data/ABCDEF/x.htm",  // non-numeric segment
            "not a url at all"
    })
    void yieldsNullForUnparseableUrls(String url) {
        assertThat(CikExtractor.fromFilingUrl(url)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void yieldsNullForNullBlankOrEmpty(String url) {
        assertThat(CikExtractor.fromFilingUrl(url)).isNull();
    }
}
