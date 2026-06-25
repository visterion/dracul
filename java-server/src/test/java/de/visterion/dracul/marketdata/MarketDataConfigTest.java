package de.visterion.dracul.marketdata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataConfigTest {

    @Test
    void defaultYahooUserAgentIsNotTheBlockedLinuxVariant() {
        assertThat(MarketDataConfig.DEFAULT_YAHOO_USER_AGENT)
                .as("Yahoo 429s the Linux-Chrome UA; default must be a non-blocked browser UA")
                .doesNotContain("X11; Linux")
                .doesNotContain("Linux x86_64")
                .contains("Mozilla/5.0")
                .contains("Windows");
    }
}
