package de.visterion.dracul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class MeControllerIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new MappingJackson2HttpMessageConverter(objectMapper)); })
                .build();
    }

    @Test void meReturnsXDevUser() {
        var body = rest.get().uri("/api/me").header("X-Dev-User", "alice@x.com")
                .retrieve().body(JsonNode.class);
        assertThat(body.get("email").asText()).isEqualTo("alice@x.com");
    }

    @Test void meDefaultsWithoutHeader() {
        var body = rest.get().uri("/api/me").retrieve().body(JsonNode.class);
        assertThat(body.get("email").asText()).isEqualTo("default");
    }
}
