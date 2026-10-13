package de.visterion.dracul.benchmark;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.benchmark.import-token=test-import-token")
class BenchmarkImportControllerIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired JdbcClient jdbc;
    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build();
    }

    private static String randomSymbol() {
        StringBuilder sb = new StringBuilder("SYN");
        ThreadLocalRandom.current().ints(6, 'A', 'Z' + 1).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    private JsonNode post(Object body, String auth) {
        return rest.post().uri("/api/benchmark/candidates/import")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(JsonNode.class);
    }

    @Test
    void importIsIdempotentAndStoresBackfillRows() {
        String sym1 = randomSymbol();
        String sym2 = randomSymbol();
        List<Map<String, Object>> body = List.of(Map.of(
                "agent", "strigoi-merger",
                "fetched_at", "2026-07-14T08:00:44.803367Z",
                "items", List.of(
                        Map.of("symbol", sym1, "companyName", "Synthetic One", "offerPrice", 12.5),
                        Map.of("symbol", "", "companyName", "Synthetic Two Corp (" + sym2 + ", " + sym2 + "-PA)"),
                        Map.of("companyName", "Synthetic Holdings  (CIK 0000000001)"))));

        JsonNode first = post(body, "Bearer test-import-token");
        JsonNode second = post(body, "Bearer test-import-token");

        assertThat(first.path("stored").asInt()).isEqualTo(2);
        assertThat(first.path("symbolLess").asInt()).isEqualTo(1);
        assertThat(second.path("stored").asInt()).isZero();
        assertThat(second.path("duplicate").asInt()).isEqualTo(2);
        List<String> sources = jdbc.sql("SELECT source FROM screen_candidate WHERE symbol IN (:a, :b)")
                .param("a", sym1).param("b", sym2).query(String.class).list();
        assertThat(sources).containsExactly("BACKFILL", "BACKFILL");
    }

    @Test
    void wrongBearerIsRejected() {
        assertThatThrownBy(() -> post(List.of(), "Bearer wrong"))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }
}
