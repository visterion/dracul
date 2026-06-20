package de.visterion.dracul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.visterion.dracul.gropar.ExitSignal;
import de.visterion.dracul.gropar.ExitSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class ExitSignalScopeIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired ExitSignalRepository repo;
    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new MappingJackson2HttpMessageConverter(objectMapper)); })
                .build();
    }

    @Test
    void exitSignalsAreScopedToCurrentUser() {
        seed("ESA", "alice@x.com");
        seed("ESB", "bob@x.com");

        JsonNode aliceSignals = rest.get().uri("/api/exit-signals")
                .header("X-Dev-User", "alice@x.com").retrieve().body(JsonNode.class);

        var symbols = StreamSupport.stream(aliceSignals.spliterator(), false)
                .map(n -> n.get("symbol").asText()).toList();
        assertThat(symbols).contains("ESA");
        assertThat(symbols).doesNotContain("ESB");
    }

    private void seed(String symbol, String userId) {
        repo.insert(new ExitSignal(
                UUID.randomUUID().toString(),
                null,                       // watchlistItemId (nullable)
                symbol,
                "SELL",
                List.of(),                  // firedRules
                null,                       // gainLossPct
                "INTACT",                   // thesisStatus
                "test rationale",
                null,                       // confidence
                null,                       // vistierieRunId
                Instant.now().toString()    // runAt
        ), userId);
    }
}
