package de.visterion.dracul.marketdata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

@Configuration
class MarketDataConfig {

    @Bean
    RestClient yahooRestClient(@Value("${dracul.marketdata.yahoo.base-url:https://query1.finance.yahoo.com}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    RestClient twelveDataRestClient(
            @Value("${dracul.marketdata.twelvedata.base-url:https://api.twelvedata.com}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "stubMarketDataPort")
    FallbackMarketDataPort fallbackMarketDataPort(
            FinnhubMarketDataAdapter finnhub,
            TwelveDataMarketDataAdapter twelveData,
            YahooMarketDataAdapter yahoo) {
        // Finnhub (60/min) first for quotes(); resolve() falls through to Twelve Data (history) then Yahoo.
        return new FallbackMarketDataPort(finnhub, new FallbackMarketDataPort(twelveData, yahoo));
    }
}
