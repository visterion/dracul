package de.visterion.dracul.hunting.edgar;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EdgarCikResolverTest {

    @Test
    void resolvesTickerToZeroPaddedCik() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://www.sec.gov");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String json = new String(new ClassPathResource("fixtures/edgar-company-tickers.json")
                .getInputStream().readAllBytes());
        server.expect(anything()).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var resolver = new EdgarCikResolver(builder.build());
        assertThat(resolver.cik("AAPL")).contains("0000320193");
        assertThat(resolver.cik("aapl")).contains("0000320193");
        assertThat(resolver.cik("ZZZZ")).isEmpty();
    }
}
