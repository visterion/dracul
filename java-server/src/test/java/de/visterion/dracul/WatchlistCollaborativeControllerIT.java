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
class WatchlistCollaborativeControllerIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired StubMarketDataPort stub;
    RestClient rest;

    @BeforeEach
    void setUp() {
        stub.reset();
        stub.register("AAPL", "Apple Inc", 190.0);
        stub.register("MSFT", "Microsoft Corp", 420.0);
        rest = RestClient.builder().baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build();
        deleteIfExists("alice@x.com", "AAPL");
        deleteIfExists("bob@x.com", "MSFT");
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

    private JsonNode addAs(String user, String symbol) {
        return rest.post().uri("/api/watchlist").header("X-Dev-User", user)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("symbol", symbol, "tag", "TRACKING"))
                .retrieve().body(JsonNode.class);
    }

    @Test void readAllWriteOwn() {
        var aliceItem = addAs("alice@x.com", "AAPL");
        addAs("bob@x.com", "MSFT");

        var all = rest.get().uri("/api/watchlist").header("X-Dev-User", "carol@x.com")
                .retrieve().body(JsonNode.class);
        var owners = StreamSupport.stream(all.spliterator(), false)
                .map(n -> n.get("owner").asText()).distinct().toList();
        assertThat(owners).contains("alice@x.com", "bob@x.com");

        String aliceId = aliceItem.get("id").asText();
        int forbidden = rest.delete().uri("/api/watchlist/" + aliceId)
                .header("X-Dev-User", "bob@x.com").retrieve()
                .onStatus(s -> true, (rq, rs) -> {})
                .toBodilessEntity().getStatusCode().value();
        assertThat(forbidden).isEqualTo(403);

        int ok = rest.delete().uri("/api/watchlist/" + aliceId).header("X-Dev-User", "alice@x.com")
                .retrieve().onStatus(s -> true, (rq, rs) -> {})
                .toBodilessEntity().getStatusCode().value();
        assertThat(ok).isEqualTo(204);
    }
}
