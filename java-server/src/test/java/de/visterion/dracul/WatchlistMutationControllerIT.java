package de.visterion.dracul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.visterion.dracul.marketdata.StubMarketDataPort;
import de.visterion.dracul.marketdata.StubMarketDataPortConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ContainerConfig.class, StubMarketDataPortConfig.class})
@ActiveProfiles("dev")
class WatchlistMutationControllerIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper om;
    @Autowired StubMarketDataPort stub;
    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear();
                    c.add(new MappingJackson2HttpMessageConverter(om)); })
                .build();
        stub.reset();
        deleteIfExists("TSLA");
    }

    @AfterEach
    void cleanUp() {
        deleteIfExists("TSLA");
    }

    private void deleteIfExists(String ticker) {
        JsonNode list = rest.get().uri("/api/watchlist").retrieve().body(JsonNode.class);
        StreamSupport.stream(list.spliterator(), false)
                .filter(n -> ticker.equals(n.get("ticker").asText()))
                .findFirst()
                .ifPresent(n -> rest.delete().uri("/api/watchlist/" + n.get("id").asText())
                        .retrieve().toBodilessEntity());
    }

    @Test
    void postCreatesItemUsingMarketDataStub() {
        stub.register("TSLA", "Tesla Inc", 950.0);

        JsonNode resp = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "TSLA", "tag", "TRACKING"))
                .retrieve().body(JsonNode.class);

        assertThat(resp.get("ticker").asText()).isEqualTo("TSLA");
        assertThat(resp.get("companyName").asText()).isEqualTo("Tesla Inc");
        assertThat(resp.get("currentPrice").asDouble()).isEqualTo(950.0);
        assertThat(resp.get("tag").asText()).isEqualTo("TRACKING");
    }

    @Test
    void postIdempotentForExistingSymbol() {
        stub.register("TSLA", "Tesla Inc", 950.0);

        JsonNode first = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "TSLA", "tag", "HELD"))
                .retrieve().body(JsonNode.class);

        JsonNode second = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "TSLA", "tag", "TRACKING"))
                .retrieve().body(JsonNode.class);

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(second.get("tag").asText()).isEqualTo("HELD");
    }

    @Test
    void postMergesSourceVerdictIdWhenPreviouslyNull() {
        stub.register("TSLA", "Tesla Inc", 950.0);

        rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "TSLA", "tag", "TRACKING"))
                .retrieve().toBodilessEntity();

        String verdictId = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class)
                .get("verdicts").get(0).get("id").asText();

        JsonNode merged = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "TSLA", "tag", "TRACKING",
                              "sourceVerdictId", verdictId))
                .retrieve().body(JsonNode.class);

        assertThat(merged.get("verdictId").asText()).isEqualTo(verdictId);
    }

    @Test
    void postReturns422WhenMarketDataNotFound() {
        assertThatThrownBy(() -> rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "ZZZZ", "tag", "TRACKING"))
                .retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class, ex ->
                    assertThat(ex.getStatusCode().value()).isEqualTo(422));
    }

    @Test
    void postReturns502WhenMarketDataUnavailable() {
        stub.forceUnavailable();
        assertThatThrownBy(() -> rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "AAPL", "tag", "HELD"))
                .retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpServerErrorException.class, ex ->
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void postReturns400ForInvalidSymbol() {
        assertThatThrownBy(() -> rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "lowercase", "tag", "HELD"))
                .retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class, ex ->
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void patchUpdatesTag() {
        stub.register("TSLA", "Tesla Inc", 950.0);
        JsonNode created = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "TSLA", "tag", "HELD"))
                .retrieve().body(JsonNode.class);

        JsonNode patched = rest.patch().uri("/api/watchlist/" + created.get("id").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tag", "TRACKING"))
                .retrieve().body(JsonNode.class);

        assertThat(patched.get("tag").asText()).isEqualTo("TRACKING");
    }

    @Test
    void deleteRemovesItem() {
        stub.register("TSLA", "Tesla Inc", 950.0);
        JsonNode created = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "TSLA", "tag", "HELD"))
                .retrieve().body(JsonNode.class);

        var resp = rest.delete().uri("/api/watchlist/" + created.get("id").asText())
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().value()).isEqualTo(204);

        JsonNode list = rest.get().uri("/api/watchlist").retrieve().body(JsonNode.class);
        boolean stillPresent = StreamSupport.stream(list.spliterator(), false)
                .anyMatch(n -> "TSLA".equals(n.get("ticker").asText()));
        assertThat(stillPresent).isFalse();
    }
}
