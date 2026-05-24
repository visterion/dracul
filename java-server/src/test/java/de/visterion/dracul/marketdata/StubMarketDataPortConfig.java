package de.visterion.dracul.marketdata;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class StubMarketDataPortConfig {

    @Bean
    @Primary
    public StubMarketDataPort stubMarketDataPort() { return new StubMarketDataPort(); }
}
