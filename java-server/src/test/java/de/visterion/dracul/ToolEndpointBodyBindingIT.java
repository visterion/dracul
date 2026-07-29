package de.visterion.dracul;

import de.visterion.dracul.hunting.agora.AgoraEarnings;
import de.visterion.dracul.strigoi.echo.EchoEnrichmentService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/** T3: a tool body that fails to bind (truncated/malformed JSON, or a JSON array where a map is
 *  expected) must never surface as a bare 4xx from a hunter tool endpoint — a 4xx there makes
 *  Vistierie terminate the whole agent run and discard every prey already produced. {@code
 *  /complete} is deliberately exempt (still 400) and the token check must still run even though
 *  the body never bound. All bodies are posted as raw strings via {@link HttpClient}, not
 *  serialised objects, so the body genuinely fails Jackson binding — the exact case
 *  {@link de.visterion.dracul.webhook.HuntController}'s exception handler is built for.
 *
 *  <p>Mirrors the {@code @MockitoBean} set of {@code StrigoiEchoWebhookControllerIT} so the
 *  Spring context can be reused across the two test classes instead of starting a second one. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.strigoi.echo.enabled=true",
        "dracul.strigoi.echo.webhook-token=test-echo-token",
        "dracul.public-url=http://test.invalid:9090"
})
class ToolEndpointBodyBindingIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @MockitoBean AgoraEarnings earnings;
    @MockitoBean EchoEnrichmentService enrichment;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String bearer, String rawBody) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rawBody));
        if (bearer != null) {
            builder.header("Authorization", bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode bodyOf(HttpResponse<String> r) {
        return objectMapper.readTree(r.body());
    }

    private JsonNode healthOf(HttpResponse<String> r) {
        return bodyOf(r).path("output").path("data_source_health");
    }

    @Test
    void truncatedBodyReturnsUnavailableNotBadRequest() throws Exception {
        var r = post("/api/strigoi-echo/tools/fetch-candidates", "Bearer test-echo-token", "{\"a\":");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(healthOf(r).path("status").asText()).isEqualTo("unavailable");
        assertThat(healthOf(r).path("detail").asText()).startsWith("tool-guard: ");
    }

    @Test
    void arrayBodyReturnsUnavailable() throws Exception {
        var r = post("/api/strigoi-echo/tools/fetch-candidates", "Bearer test-echo-token", "[]");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(healthOf(r).path("status").asText()).isEqualTo("unavailable");
    }

    @Test
    void truncatedBodyWithoutTokenStill401() throws Exception {
        var r = post("/api/strigoi-echo/tools/fetch-candidates", null, "{\"a\":");

        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void completeWithTruncatedBodyStill400() throws Exception {
        var r = post("/api/strigoi-echo/complete", "Bearer test-echo-token", "{\"a\":");

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(bodyOf(r).path("error").asText()).isEqualTo("VALIDATION_ERROR");
    }

    /** {@code outputKeyFor} still returns the controller default ("candidates") for fetchNews
     *  until Task 4 overrides it for echo's second tool endpoint. Disabled — not deleted — so
     *  Task 4 can re-enable it as the regression anchor for that override, per the task-3
     *  brief's explicit instruction to write this case now rather than leave it unwritten. */
    @Test
    @Disabled("enabled by Task 4, which overrides outputKeyFor for StrigoiEchoWebhookController#fetchNews")
    void fetchNewsWithTruncatedBodyUsesTheNewsKey() throws Exception {
        var r = post("/api/strigoi-echo/tools/fetch-news", "Bearer test-echo-token", "{\"a\":");

        assertThat(bodyOf(r).path("output").has("news")).isTrue();
        assertThat(bodyOf(r).path("output").has("candidates")).isFalse();
    }
}
