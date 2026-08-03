package de.visterion.dracul.webhook;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.marketdata.MarketDataException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Coverage for the failure envelope introduced on {@link HuntController}: {@link
 *  HuntController#ok}, {@link HuntController#unavailable}, and {@link
 *  HuntController#GUARD_MARKER}. No Spring context needed — these are static/protected
 *  helpers exercised directly. */
class HuntControllerToolFailureTest {

    @Test
    void unavailableEnvelopeCarriesAllFourHealthKeysAndTruncatesDetail() {
        String longDetail = "x".repeat(2000);
        Map<String, Object> out = HuntController.unavailable(
                Map.of("candidates", List.of()), "strigoi-test", HuntController.GUARD_MARKER + longDetail);

        assertThat(out).containsKey("candidates");
        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) out.get("data_source_health");
        assertThat(health).containsOnlyKeys("status", "source", "detail", "checked_at");
        assertThat(health.get("status")).isEqualTo("unavailable");
        assertThat(health.get("source")).isEqualTo("strigoi-test");
        assertThat((String) health.get("detail")).startsWith("tool-guard: ").hasSize(500);
    }

    @Test
    void okWrapsOutputInTheEnvelope() {
        var response = HuntController.ok(Map.of("candidates", List.of()));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsOnlyKeys("output");
    }

    /** Direct coverage of {@link HuntController#healthyPayload}, the cache admission
     *  predicate. Both negative branches must return {@code false} — a payload without an
     *  {@code output} key, or with {@code output} but no {@code data_source_health} block, is
     *  NOT provably healthy and must never be cached. A positive control confirms the
     *  predicate still admits a genuinely healthy payload. */
    @Test
    void healthyPayloadRejectsPayloadsMissingOutputOrHealthBlock() {
        assertThat(HuntController.healthyPayload(Map.of())).isFalse();
        assertThat(HuntController.healthyPayload(
                Map.of("output", Map.of("candidates", List.of())))).isFalse();
        assertThat(HuntController.healthyPayload(
                Map.of("output", Map.of("data_source_health", Map.of("status", "healthy")))))
                .isTrue();
    }

    // --- healthOf / healthyPayload degradation flags ------------------------------------------

    @Test void healthMapOmitsDegradationFlagsWhenFalse() {
        Map<String, Object> m = HuntController.healthOf(
                de.visterion.dracul.hunting.DataSourceHealth.healthy("agora"));
        assertThat(m).containsEntry("status", "healthy");
        assertThat(m).doesNotContainKey("partial");
        assertThat(m).doesNotContainKey("truncated");
    }

    @Test void healthMapCarriesDegradationFlagsWhenSet() {
        Map<String, Object> m = HuntController.healthOf(
                de.visterion.dracul.hunting.DataSourceHealth.degraded(
                        "agora", "window not fully covered", true, true));
        assertThat(m).containsEntry("status", "healthy");
        assertThat(m).containsEntry("partial", true);
        assertThat(m).containsEntry("truncated", true);
        assertThat(m).containsEntry("detail", "window not fully covered");
    }

    /** A degraded payload must not be frozen for the full cache TTL: Agora itself keeps partial
     *  earnings answers for only 600s, and caching one here would overrule that. */
    @Test void degradedPayloadIsNotCacheable() {
        Map<String, Object> payload = Map.of("output", Map.of(
                "candidates", List.of(),
                "data_source_health", HuntController.healthOf(
                        de.visterion.dracul.hunting.DataSourceHealth.degraded(
                                "agora", "partial", true, false))));
        assertThat(HuntController.healthyPayload(payload)).isFalse();
    }

    @Test void cleanPayloadIsCacheable() {
        Map<String, Object> payload = Map.of("output", Map.of(
                "candidates", List.of(),
                "data_source_health", HuntController.healthOf(
                        de.visterion.dracul.hunting.DataSourceHealth.healthy("agora"))));
        assertThat(HuntController.healthyPayload(payload)).isTrue();
    }

    // --- handleFetch structural-catch coverage -----------------------------------------------

    private static final String TOKEN = "t";

    @Test
    void noSuchElementFromHuntBecomesUnavailableNotFourOhFour() {
        var controller = throwingController(new NoSuchElementException("boom"));
        var response = controller.callFetch("Bearer t", Map.of());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(health(response).get("status")).isEqualTo("unavailable");
        assertThat((String) health(response).get("detail")).startsWith("tool-guard: ");
    }

    @Test
    void marketDataNotFoundFromHuntBecomesUnavailable() {
        var controller = throwingController(
                new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no such symbol"));
        var response = controller.callFetch("Bearer t", Map.of());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(health(response).get("status")).isEqualTo("unavailable");
        assertThat((String) health(response).get("detail")).startsWith("tool-guard: ");
    }

    @Test
    void plainRuntimeExceptionFromHuntBecomesUnavailable() {
        var controller = throwingController(new IllegalStateException("boom"));
        var response = controller.callFetch("Bearer t", Map.of());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(health(response).get("status")).isEqualTo("unavailable");
        assertThat((String) health(response).get("detail")).startsWith("tool-guard: ");
    }

    @Test
    void errorsAreNotSwallowed() {
        var controller = throwingController(new StackOverflowError());
        assertThatThrownBy(() -> controller.callFetch("Bearer t", Map.of()))
                .isInstanceOf(StackOverflowError.class);
    }

    @Test
    void overriddenOutputKeyIsUsedInTheFailureEnvelope() {
        var controller = throwingController(new IllegalStateException("boom"));
        controller.useOutputKey("clusters");
        var response = controller.callFetch("Bearer t", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) response.getBody().get("output");
        assertThat(output).containsKey("clusters");
        assertThat(output.get("clusters")).isEqualTo(List.of());
    }

    /** Anchor against the lazarus regression: a 200 alone would also come out of the new
     *  structural catch (a null-body NPE from {@link HuntController#lookbackDays} would be
     *  swallowed just like any other RuntimeException), so this asserts that {@code hunt()}
     *  actually ran and produced a genuinely healthy result — not merely that a 200 came back. */
    @Test
    void nullBodyReachesHuntAndStaysHealthy() {
        var controller = recordingController();
        var response = controller.callFetch("Bearer t", null);
        assertThat(controller.huntWasEntered()).isTrue();
        assertThat(health(response).get("status")).isEqualTo("healthy");
    }

    /** {@link HuntController#handleFetch} is the one place all six hunters pass through, so the
     *  item count and degradation flags are logged there instead of six easily-forgotten call
     *  sites. Without this line, "0 candidates fetched" and "100 candidates fetched, all screened
     *  out" are indistinguishable in the app log — the exact ambiguity that cost a full forensic
     *  pass through Agora's logs and the Vistierie transcript on 2026-08-03. */
    @Test
    void logsItemCountAndDegradationPerFetch() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TestController.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var controller = new TestController(() -> new DataSourceResult<>(List.of("a", "b"),
                    de.visterion.dracul.hunting.DataSourceHealth.degraded(
                            "strigoi-test", "window not fully covered", true, false)));
            controller.callFetch("Bearer t", Map.of());
            assertThat(appender.list).anyMatch(e ->
                    e.getFormattedMessage().matches(
                            ".*fetch: items=2 \\(partial=true truncated=false status=healthy\\).*"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    // --- test doubles ------------------------------------------------------------------------

    private static TestController throwingController(RuntimeException toThrow) {
        return new TestController(() -> { throw toThrow; });
    }

    private static TestController throwingController(Error toThrow) {
        return new TestController(() -> { throw toThrow; });
    }

    private static TestController recordingController() {
        return new TestController(() -> DataSourceResult.healthy("strigoi-test", List.of()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> health(ResponseEntity<Map<String, Object>> response) {
        Map<String, Object> output = (Map<String, Object>) response.getBody().get("output");
        return (Map<String, Object>) output.get("data_source_health");
    }

    /** Minimal concrete {@link HuntController} for exercising {@link
     *  HuntController#handleFetch} without a Spring context. Only {@code cache} is actually
     *  read by {@code handleFetch}, so the remaining collaborators (prey persistence, HiveMem
     *  memory writes) are irrelevant here and passed as {@code null}. */
    private static class TestController extends HuntController {
        private final Supplier<DataSourceResult<?>> huntFn;
        private boolean entered = false;
        private String outputKey = "candidates";

        TestController(Supplier<DataSourceResult<?>> huntFn) {
            super(TOKEN, null, new ToolFetchCache(new AgentToolCatalog(List.of()), 300), null, null);
            this.huntFn = huntFn;
            // patternRepo is @Autowired-only in production; this plain unit test never goes
            // through a Spring context, so wire an empty provider by hand — handleFetch calls
            // patternRepo.ifAvailable(...) unconditionally and would NPE on the field's default
            // null otherwise, which is not the failure this test class is about.
            setField("patternRepo", emptyProvider());
        }

        private void setField(String name, Object value) {
            try {
                Field f = HuntController.class.getDeclaredField(name);
                f.setAccessible(true);
                f.set(this, value);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        private static <T> ObjectProvider<T> emptyProvider() {
            return new ObjectProvider<>() {
                @Override public T getObject() { throw new UnsupportedOperationException(); }
                @Override public T getIfAvailable() { return null; }
            };
        }

        @Override protected String agentName() { return "strigoi-test"; }

        @Override protected DataSourceResult<?> hunt(Map<String, Object> input) {
            entered = true;
            // Mirrors strigoi-echo's hunt(), which reads the body via the unguarded
            // lookbackDays(body, ...) — unlike lazarus, whose hunt() never touches body and so
            // survives a null one today. Reading it here is what makes this test double fail
            // with the NPE the brief describes before Task 2's null-body substitution lands.
            lookbackDays(input, 7, 1, 30);
            return huntFn.get();
        }

        @Override protected String defaultAnomalyType() { return "TEST"; }
        @Override protected String toolName() { return "fetch_test_candidates"; }
        @Override protected String fetchOutputKey() { return outputKey; }

        void useOutputKey(String key) { this.outputKey = key; }

        boolean huntWasEntered() { return entered; }

        ResponseEntity<Map<String, Object>> callFetch(String auth, Map<String, Object> body) {
            return handleFetch(auth, body);
        }
    }
}
