package de.visterion.dracul.hunting.finnhub;

import de.visterion.dracul.strigoi.echo.EarningsObservation;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FinnhubEarningsAdapterTest {

    @Test
    void parsesCalendarAndComputesSurprise() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://finnhub.io/api/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String json = new String(new ClassPathResource("fixtures/finnhub-earnings-calendar.json")
                .getInputStream().readAllBytes());
        server.expect(anything()).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        var adapter = new FinnhubEarningsAdapter(builder.build(), "test-key");
        var result = adapter.recent(LocalDate.of(2026, 4, 25), LocalDate.of(2026, 5, 2));

        assertThat(result.health().isHealthy()).isTrue();
        assertThat(result.items()).hasSize(1); // NODATA row dropped (null eps)
        EarningsObservation o = result.items().get(0);
        assertThat(o.symbol()).isEqualTo("AAPL");
        assertThat(o.epsActual()).isEqualByComparingTo("1.65");
        assertThat(o.epsSurprisePercent()).isEqualByComparingTo("10.0"); // (1.65-1.50)/1.50*100
        assertThat(o.revenueActual()).isEqualByComparingTo("95000000000");
    }

    @Test
    void returnsUnavailableOnHttpError() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://finnhub.io/api/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        var adapter = new FinnhubEarningsAdapter(builder.build(), "test-key");
        var result = adapter.recent(LocalDate.of(2026, 4, 25), LocalDate.of(2026, 5, 2));

        assertThat(result.health().isHealthy()).isFalse();
        assertThat(result.items()).isEmpty();
    }

    @Test
    void unavailableWhenNoKey() {
        var adapter = new FinnhubEarningsAdapter(RestClient.builder().build(), "");
        var result = adapter.recent(LocalDate.of(2026, 4, 25), LocalDate.of(2026, 5, 2));
        assertThat(result.health().isHealthy()).isFalse();
    }
}
