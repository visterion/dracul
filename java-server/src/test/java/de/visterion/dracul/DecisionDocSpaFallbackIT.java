package de.visterion.dracul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Regression: pins that {@code GET /api/decision-doc} is answered by
 * {@link de.visterion.dracul.report.DecisionDocController} (a content-level
 * 404 when {@code dracul.decision-doc.path} is unset) and NOT shadowed by the
 * {@link SpaFallbackController}, which would otherwise serve a 200 text/html
 * (its {@code /{p1}/{p2}} pattern also matches the dot-free two-segment path
 * {@code /api/decision-doc}).
 *
 * <p>Why the {@link ForceSpaFallback} config: {@code SpaFallbackController} is
 * {@code @ConditionalOnResource("classpath:static/index.html")}. The test
 * classpath has NO {@code static/index.html} (the built SPA is only present in
 * the prod image), so under normal component scanning the fallback bean is
 * absent and the shadowing scenario would not be exercised at all. Adding the
 * resource to {@code src/test/resources} is not an option: it would activate
 * the fallback for every {@code @SpringBootTest} and break
 * {@link SpaFallbackControllerIT}, which asserts the fallback is inactive.
 * Instead we force-register the controller via a plain {@code @Bean} factory
 * method (class-level {@code @Conditional} does not apply to manual
 * {@code @Bean} methods). This faithfully reproduces the prod state where BOTH
 * controllers are present, so the test genuinely proves the precedence: the
 * exact {@code /api/decision-doc} mapping wins over the SPA URI-variable
 * pattern. If a future dev makes {@code DecisionDocController} conditional and
 * its bean disappears, only the SPA pattern remains and this test fails loudly
 * (200 text/html instead of 404).
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({ContainerConfig.class, DecisionDocSpaFallbackIT.ForceSpaFallback.class})
@ActiveProfiles("dev")
class DecisionDocSpaFallbackIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class ForceSpaFallback {
        // A manual @Bean bypasses the class-level @ConditionalOnResource, so the
        // SPA fallback is active even though static/index.html is absent from the
        // test classpath. The ClassPathResource field is constructed lazily and
        // never read here (DecisionDocController answers first), so its absence is
        // harmless.
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
    void decisionDocPathUnsetReturns404NotSpaHtml() {
        // .exchange(...) lets us inspect status + headers without RestClient
        // throwing on the 4xx.
        ResponseEntity<Void> resp = rest.get()
                .uri("/api/decision-doc")
                .exchange((request, response) ->
                        ResponseEntity.status(response.getStatusCode())
                                .headers(h -> h.addAll(response.getHeaders()))
                                .build());

        MediaType contentType = resp.getHeaders().getContentType();

        // The invariant: DecisionDocController answers with 404, NOT the SPA
        // fallback. The status is the decisive signal — the SPA fallback yields
        // 200 text/html in prod (where static/index.html exists) and 401 in this
        // test (the missing index.html read throws and is caught+rethrown as 401
        // by CloudflareAccessFilter's bypass try/catch); either way it is never
        // 404. The controller's 404 is a bodiless ResponseEntity.notFound(), so
        // it carries NO Content-Type header (empirically null here) — hence the
        // extra text/html guard is null-tolerant. It is defence for the prod
        // 200-text/html shadowing case rather than the primary assertion.
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        if (contentType != null) {
            assertThat(contentType.isCompatibleWith(MediaType.TEXT_HTML))
                    .as("must NOT be the SPA fallback's text/html")
                    .isFalse();
        }
    }
}
