package de.visterion.dracul.marketdata;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the real FxService (which makes live Yahoo HTTP calls) with a no-op stub.
 * FxService(null) is safe: the constructor only assigns the field, and rate() catches
 * any NullPointerException, returning null, which causes convert() to return the amount
 * unchanged (identity conversion).
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubFxServiceConfig {

    @Bean
    @Primary
    public FxService stubFxService() {
        return new FxService(null);
    }
}
