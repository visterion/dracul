package de.visterion.dracul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class SpaFallbackControllerIT {

    @LocalServerPort int port;
    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void apiStatusReturnsJson() {
        ResponseEntity<String> resp = rest.get()
                .uri("/api/status")
                .retrieve()
                .toEntity(String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getHeaders().getContentType().toString())
                .contains("application/json");
    }

    @Test
    void dottedPathReturns404WhenNoStaticFile() {
        // Regex [^\.]* must NOT match paths with a dot extension.
        // SpaFallbackController is inactive in tests (static/index.html not present).
        // A dotted path not matching any controller returns 404.
        try {
            rest.get().uri("/assets/main.abc123.js")
                    .retrieve().toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(404);
            return;
        }
        org.junit.jupiter.api.Assertions.fail("Expected 404 for dotted path");
    }
}
