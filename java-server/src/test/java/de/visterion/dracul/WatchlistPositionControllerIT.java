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
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ContainerConfig.class, StubMarketDataPortConfig.class})
@ActiveProfiles("dev")
class WatchlistPositionControllerIT {

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
    void patchPositionSetsEntryPriceAndShareCount() {
        stub.register("TSLA", "Tesla Inc", 950.0);
        JsonNode created = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "TSLA", "tag", "HELD"))
                .retrieve().body(JsonNode.class);
        String id = created.get("id").asText();

        JsonNode patched = rest.patch().uri("/api/watchlist/" + id + "/position")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("entryPrice", 900.0, "shareCount", 10.0))
                .retrieve().body(JsonNode.class);

        assertThat(patched.get("entryPrice").asDouble()).isEqualTo(900.0);
        assertThat(patched.get("shareCount").asDouble()).isEqualTo(10.0);
    }

    @Test
    void patchPositionAcceptsNullToClear() {
        stub.register("TSLA", "Tesla Inc", 950.0);
        String id = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "TSLA", "tag", "HELD"))
                .retrieve().body(JsonNode.class).get("id").asText();

        rest.patch().uri("/api/watchlist/" + id + "/position")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("entryPrice", 900.0, "shareCount", 10.0))
                .retrieve().toBodilessEntity();

        var map = new HashMap<String, Object>();
        map.put("entryPrice", null);
        map.put("shareCount", null);
        JsonNode cleared = rest.patch().uri("/api/watchlist/" + id + "/position")
                .contentType(MediaType.APPLICATION_JSON).body(map)
                .retrieve().body(JsonNode.class);

        assertThat(cleared.get("entryPrice").isNull()).isTrue();
        assertThat(cleared.get("shareCount").isNull()).isTrue();
    }

    @Test
    void patchPositionRejectsNegative() {
        stub.register("TSLA", "Tesla Inc", 950.0);
        String id = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "TSLA", "tag", "HELD"))
                .retrieve().body(JsonNode.class).get("id").asText();

        var resp = rest.patch().uri("/api/watchlist/" + id + "/position")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("entryPrice", -5.0, "shareCount", 10.0))
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(resp).isEqualTo(400);
    }

    @Test
    void patchPositionRejectsZeroEntryPrice() {
        stub.register("TSLA", "Tesla Inc", 950.0);
        String id = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "TSLA", "tag", "HELD"))
                .retrieve().body(JsonNode.class).get("id").asText();

        var resp = rest.patch().uri("/api/watchlist/" + id + "/position")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("entryPrice", 0.0, "shareCount", 10.0))
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(resp).isEqualTo(400);
    }

    @Test
    void patchPositionUnknownIdIs404() {
        var resp = rest.patch().uri("/api/watchlist/00000000-0000-0000-0000-000000000999/position")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("entryPrice", 1.0, "shareCount", 1.0))
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(resp).isEqualTo(404);
    }
}
