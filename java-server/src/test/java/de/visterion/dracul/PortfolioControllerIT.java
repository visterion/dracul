package de.visterion.dracul;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import de.visterion.dracul.marketdata.StubFxServiceConfig;
import de.visterion.dracul.marketdata.StubMarketDataPort;
import de.visterion.dracul.marketdata.StubMarketDataPortConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ContainerConfig.class, StubMarketDataPortConfig.class, StubFxServiceConfig.class})
@ActiveProfiles("dev")
class PortfolioControllerIT {

    private static final String ALICE = "pf-alice@x.com";
    private static final String BOB = "pf-bob@x.com";

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired StubMarketDataPort stub;
    RestClient rest;

    @BeforeEach
    void setUp() {
        stub.reset();
        stub.register("PFA", "Portfolio A Corp", 100.0);
        stub.register("PFB", "Portfolio B Corp", 200.0);
        stub.register("PFC", "Portfolio C Corp", 300.0);
        rest = RestClient.builder().baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build();
        deleteIfExists(ALICE, "PFA");
        deleteIfExists(ALICE, "PFC");
        deleteIfExists(BOB, "PFB");
    }

    private void deleteIfExists(String user, String ticker) {
        JsonNode list = rest.get().uri("/api/watchlist").header("X-Dev-User", user)
                .retrieve().body(JsonNode.class);
        StreamSupport.stream(list.spliterator(), false)
                .filter(n -> ticker.equals(n.get("ticker").asText())
                          && user.equals(n.get("owner").asText()))
                .findFirst()
                .ifPresent(n -> rest.delete().uri("/api/watchlist/" + n.get("id").asText())
                        .header("X-Dev-User", user).retrieve().toBodilessEntity());
    }

    private String addPosition(String user, String symbol, double entry, double shares) {
        JsonNode created = rest.post().uri("/api/watchlist").header("X-Dev-User", user)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", symbol, "tag", "HELD"))
                .retrieve().body(JsonNode.class);
        String id = created.get("id").asText();
        rest.patch().uri("/api/watchlist/" + id + "/position").header("X-Dev-User", user)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("entryPrice", entry, "shareCount", shares))
                .retrieve().toBodilessEntity();
        return id;
    }

    @Test
    void portfolioReturnsOnlyCurrentUsersPositions() {
        String alicePos = addPosition(ALICE, "PFA", 90.0, 10.0);
        addPosition(BOB, "PFB", 150.0, 5.0);
        rest.post().uri("/api/watchlist").header("X-Dev-User", ALICE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", "PFC", "tag", "TRACKING"))
                .retrieve().toBodilessEntity();

        JsonNode portfolio = rest.get().uri("/api/portfolio").header("X-Dev-User", ALICE)
                .retrieve().body(JsonNode.class);

        var tickers = StreamSupport.stream(portfolio.spliterator(), false)
                .map(n -> n.get("ticker").asText()).toList();
        var owners = StreamSupport.stream(portfolio.spliterator(), false)
                .map(n -> n.get("owner").asText()).distinct().toList();

        assertThat(tickers).containsExactly("PFA");
        assertThat(tickers).doesNotContain("PFB", "PFC");
        assertThat(owners).containsExactly(ALICE);
        assertThat(StreamSupport.stream(portfolio.spliterator(), false)
                .anyMatch(n -> n.get("id").asText().equals(alicePos))).isTrue();
    }
}
