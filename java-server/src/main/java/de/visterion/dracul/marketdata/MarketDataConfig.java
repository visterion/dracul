package de.visterion.dracul.marketdata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
}
