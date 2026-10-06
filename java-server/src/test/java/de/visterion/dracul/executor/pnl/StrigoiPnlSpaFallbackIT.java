package de.visterion.dracul.executor.pnl;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.SpaFallbackController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Regression for final-review finding I1: with {@code dracul.executor.enabled=false} (so
 * {@link StrigoiPnlService} does not exist), {@code GET /api/executor/pnl/strigoi} (4 dot-free
 * segments) must be answered by {@link StrigoiPnlController}'s content-level 404, NOT shadowed
 * by {@link SpaFallbackController}'s {@code /{p1}/{p2}/{p3}/{p4}} pattern, which would otherwise
 * serve a 200 text/html and make the Depots table show its error instead of hiding (contradicts
 * {@code documentation/chronicle.md}: "A 404 ... hides the section").
 *
 * <p>Same {@code ForceSpaFallback} trick as {@code de.visterion.dracul.DecisionDocSpaFallbackIT}:
 * the test classpath
 * has no {@code static/index.html}, so {@code SpaFallbackController} (which is
 * {@code @ConditionalOnResource}) is normally absent; a manual {@code @Bean} force-registers it
 * so both controllers are present, faithfully reproducing prod and proving the exact mapping
 * wins over the SPA's URI-variable pattern.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ContainerConfig.class, StrigoiPnlSpaFallbackIT.ForceSpaFallback.class})
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=false")
class StrigoiPnlSpaFallbackIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class ForceSpaFallback {
        @Bean
        SpaFallbackController spaFallbackController() {
            return new SpaFallbackController();
        }
    }

    @LocalServerPort int port;
    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void executorDisabledOverviewReturns404NotSpaHtml() {
        assertNotSpaHtml("/api/executor/pnl/strigoi?connection=depot-1");
    }

    @Test
    void executorDisabledDetailReturns404NotSpaHtml() {
        assertNotSpaHtml("/api/executor/pnl/strigoi/strigoi-syna?connection=depot-1");
    }

    private void assertNotSpaHtml(String path) {
        ResponseEntity<Void> resp = rest.get()
                .uri(path)
                .exchange((request, response) ->
                        ResponseEntity.status(response.getStatusCode())
                                .headers(h -> h.addAll(response.getHeaders()))
                                .build());

        MediaType contentType = resp.getHeaders().getContentType();

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        if (contentType != null) {
            assertThat(contentType.isCompatibleWith(MediaType.TEXT_HTML))
                    .as("must NOT be the SPA fallback's text/html")
                    .isFalse();
        }
    }
}
