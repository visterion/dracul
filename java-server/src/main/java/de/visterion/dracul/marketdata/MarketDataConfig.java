package de.visterion.dracul.marketdata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
class MarketDataConfig {

    /**
     * Yahoo 429s requests carrying a Linux-Chrome UA (e.g. "X11; Linux x86_64 … Chrome/124.0.0.0");
     * a Windows-Chrome UA returns 200. Overridable via dracul.marketdata.yahoo.user-agent.
     */
    static final String DEFAULT_YAHOO_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    @Bean
    RestClient yahooRestClient(
            @Value("${dracul.marketdata.yahoo.base-url:https://query1.finance.yahoo.com}") String baseUrl,
            @Value("${dracul.marketdata.yahoo.user-agent:}") String userAgent,
            @Value("${dracul.marketdata.yahoo.timeout-ms:5000}") long timeoutMs) {
        String ua = (userAgent == null || userAgent.isBlank()) ? DEFAULT_YAHOO_USER_AGENT : userAgent;
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs)).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder().requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader("User-Agent", ua).build();
    }
}
