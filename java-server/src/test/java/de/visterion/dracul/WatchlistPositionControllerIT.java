package de.visterion.dracul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.visterion.dracul.marketdata.StubFxServiceConfig;
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

import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.watchlist.WatchlistRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ContainerConfig.class, StubMarketDataPortConfig.class, StubFxServiceConfig.class})
@ActiveProfiles("dev")
class WatchlistPositionControllerIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper om;
    @Autowired StubMarketDataPort stub;
    @Autowired de.visterion.dracul.watchlist.WatchlistRepository watchlistRepo;
    @Autowired AppSettingsRepository settingsRepo;
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
        deleteIfExists("AAPL");
        deleteIfExists("NVDA");
    }

    @AfterEach
    void cleanUp() {
        deleteIfExists("TSLA");
        deleteIfExists("AAPL");
        deleteIfExists("NVDA");
        deleteIfExists("3750.HK");
        settingsRepo.setDisplayCurrency("EUR");
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

    @Test
    void storesNativeAndEntryCurrency() {
        stub.register("AAPL", "Apple Inc", 190.0, "USD");

        // create AAPL
        JsonNode created = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "AAPL", "tag", "HELD"))
                .retrieve().body(JsonNode.class);
        String id = created.get("id").asText();

        // set position
        rest.patch().uri("/api/watchlist/" + id + "/position")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("entryPrice", 180.0, "shareCount", 5.0))
                .retrieve().toBodilessEntity();

        // assert via repository
        var item = watchlistRepo.findById(id).orElseThrow();
        assertThat(item.currency()).isEqualTo("USD");
        assertThat(item.entryCurrency()).isEqualTo("EUR");
    }

    @Test
    void watchlistResponseCarriesDisplayCurrency() {
        // Use USD as display so native==display => identity conversion, no real FX call needed
        settingsRepo.setDisplayCurrency("USD");
        stub.register("NVDA", "Nvidia", 500.0, "USD");

        // create NVDA
        rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "NVDA", "tag", "TRACKING"))
                .retrieve().body(JsonNode.class);

        // GET /api/watchlist, find NVDA item
        JsonNode list = rest.get().uri("/api/watchlist").retrieve().body(JsonNode.class);
        JsonNode nvda = StreamSupport.stream(list.spliterator(), false)
                .filter(n -> "NVDA".equals(n.get("ticker").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("NVDA not found in watchlist"));

        assertThat(nvda.get("currency").asText()).isEqualTo("USD");
        assertThat(nvda.get("currentPrice").asDouble()).isEqualTo(500.0);
    }

    @Test
    void acceptsDigitLeadingExchangeSymbol() {
        stub.register("3750.HK", "Contemporary Amperex", 700.0, "HKD");
        var status = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "3750.HK", "tag", "HELD"))
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(status).isEqualTo(201);
        deleteIfExists("3750.HK");
    }

    @Test
    void rejectsLowercaseSymbol() {
        var status = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "abc", "tag", "HELD"))
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(status).isEqualTo(400);
    }

    @Test
    void rejectsTooLongSymbol() {
        var status = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "ABCDEFGHIJKLM", "tag", "HELD")) // 13 chars
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(status).isEqualTo(400);
    }

    @Test
    void patchPositionPersistsEntryDate() {
        stub.register("TSLA", "Tesla Inc", 950.0);
        JsonNode created = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "TSLA", "tag", "HELD"))
                .retrieve().body(JsonNode.class);
        String id = created.get("id").asText();

        rest.patch().uri("/api/watchlist/" + id + "/position")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("entryPrice", 10.0, "shareCount", 2.0, "entryDate", "2024-03-01"))
                .retrieve().toBodilessEntity();

        var risk = watchlistRepo.positionRiskByItemId().get(id);
        assertThat(risk).isNotNull();
        assertThat(risk.entryDate()).isEqualTo("2024-03-01");
    }

    @Test
    void patchPositionWithoutEntryDateLeavesEntryDateUnchanged() {
        stub.register("TSLA", "Tesla Inc", 950.0);
        JsonNode created = rest.post().uri("/api/watchlist")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "TSLA", "tag", "HELD"))
                .retrieve().body(JsonNode.class);
        String id = created.get("id").asText();

        // first patch: set entryDate
        rest.patch().uri("/api/watchlist/" + id + "/position")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("entryPrice", 10.0, "shareCount", 2.0, "entryDate", "2024-03-01"))
                .retrieve().toBodilessEntity();

        // second patch: no entryDate — must not overwrite the stored value
        rest.patch().uri("/api/watchlist/" + id + "/position")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("entryPrice", 15.0, "shareCount", 3.0))
                .retrieve().toBodilessEntity();

        var risk = watchlistRepo.positionRiskByItemId().get(id);
        assertThat(risk).isNotNull();
        assertThat(risk.entryDate()).isEqualTo("2024-03-01");
    }
}
