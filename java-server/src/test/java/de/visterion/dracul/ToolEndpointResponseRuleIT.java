package de.visterion.dracul;

import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraEarnings;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.AgoraReference;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.strigoi.echo.EchoEnrichmentService;
import de.visterion.dracul.strigoi.index.IndexDemandSnapshotter;
import de.visterion.dracul.strigoi.index.IndexDriftSnapshotter;
import de.visterion.dracul.strigoi.merger.MergerEnrichmentService;
import de.visterion.dracul.strigoi.spin.SpinBalanceSheetSnapshotter;
import de.visterion.dracul.strigoi.spin.SpinDistributionSnapshotter;
import de.visterion.dracul.strigoi.spin.SpinValuationSnapshotter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/** T6, the guard that outlives the change: no hunter tool endpoint (`/api/strigoi-*&#47;tools/**`)
 *  may ever answer with a status other than 200 or 401 (design doc §4.1).
 *
 *  <p>All six hunters run in one context (pattern: {@code AgentRegistrationParityTest}); all six
 *  webhook tokens share the same default {@code dev-token-change-me} (application.yaml), so one
 *  bearer works for every endpoint — {@link #noTokenIs401} and friends exercise every path with
 *  the SAME {@link #TOKEN} constant, which is itself the verification that the shared default
 *  actually holds rather than an assumption.
 *
 *  <p>Two things make this test worth having rather than decorative:
 *  <ol>
 *    <li>The endpoint list is derived from {@link RequestMappingHandlerMapping} and then pinned
 *        against an explicit, hand-written expectation ({@link #derivedToolEndpointListMatchesTheSevenExpectedPaths}).
 *        Every hunter is {@code @ConditionalOnProperty} with default {@code false}; a forgotten
 *        property in this class's {@code @TestPropertySource} would silently shrink the derived
 *        list — in the worst case to zero — and every {@code @MethodSource}-driven test below
 *        would pass vacuously over an empty list.</li>
 *    <li>A status of 200 alone proves nothing: {@code HuntController.handleFetch}'s
 *        {@code catch (RuntimeException)} turns EVERY exception into a 200, so a status-only
 *        matrix would be satisfied by the guard itself and would miss exactly the regression it
 *        exists to catch (a forgotten {@code body = Map.of()} substitution would NPE, the guard
 *        would answer 200, and the hunter would run empty every night). The matrix below is
 *        therefore split into rows that must run NORMALLY (200, {@code status = "healthy"},
 *        {@code detail} does NOT carry the guard's marker) and rows that are genuinely broken
 *        (200, {@code status = "unavailable"}, {@code detail} DOES carry the marker).</li>
 *  </ol>
 *
 *  <p>{@code fetch-news} (StrigoiEchoWebhookController's second tool endpoint) is the one
 *  endpoint that cannot share the generic "benign body" list: unlike the other six, a missing
 *  {@code symbol} is not tolerated input — it is the endpoint's own deliberate validation (design
 *  doc §3.3), answered with the SAME 200/unavailable/{@value #GUARD_MARKER} envelope as the
 *  structural guard, by design. It gets its own benign-input test ({@code symbol} supplied) and
 *  its own absent-body test (asserting the guarded shape, not "healthy") instead of being folded
 *  into the six-endpoint generic rows. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.strigoi.echo.enabled=true",
        "dracul.strigoi.merger.enabled=true",
        "dracul.strigoi.spin.enabled=true",
        "dracul.strigoi.index.enabled=true",
        "dracul.strigoi.insider.enabled=true",
        "dracul.strigoi.lazarus.enabled=true",
        "dracul.public-url=http://test.invalid:9090"
})
class ToolEndpointResponseRuleIT {

    /** Mirrors {@code HuntController.GUARD_MARKER}, which is {@code protected} and therefore not
     *  visible from this package — see design doc §3.1 for the source of truth. */
    private static final String GUARD_MARKER = "tool-guard: ";

    /** All six webhook tokens default to the same value (application.yaml); this constant is
     *  itself the check that the shared default holds — every parameterised test below reuses it
     *  unchanged across all seven paths. */
    private static final String TOKEN = "Bearer dev-token-change-me";

    private static final List<String> ALL_PATHS = List.of(
            "/api/strigoi-echo/tools/fetch-candidates",
            "/api/strigoi-echo/tools/fetch-news",
            "/api/strigoi-merger/tools/fetch-candidates",
            "/api/strigoi-spin/tools/fetch-candidates",
            "/api/strigoi-index/tools/fetch-candidates",
            "/api/strigoi-insider/tools/fetch-clusters",
            "/api/strigoi-lazarus/tools/fetch-candidates");

    /** The six candidate/cluster endpoints — everything except {@code fetch-news}, which needs
     *  its own benign-input test (see class javadoc). */
    private static final List<String> CANDIDATE_PATHS = ALL_PATHS.stream()
            .filter(p -> !p.endsWith("/fetch-news"))
            .toList();

    static List<String> endpoints() { return ALL_PATHS; }
    static List<String> candidateEndpoints() { return CANDIDATE_PATHS; }

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired RequestMappingHandlerMapping handlerMapping;
    @Autowired JdbcClient jdbc;

    @MockitoBean AgoraEarnings earnings;                    // echo
    @MockitoBean EchoEnrichmentService echoEnrichment;       // echo
    @MockitoBean AgoraCompanyData companyData;               // echo (fetch-news) + lazarus
    @MockitoBean AgoraFilings filings;                       // merger + insider + spin
    @MockitoBean MergerEnrichmentService mergerEnrichment;   // merger
    @MockitoBean AgoraReference reference;                   // index
    @MockitoBean IndexDemandSnapshotter demandSnapshotter;   // index
    @MockitoBean IndexDriftSnapshotter driftSnapshotter;     // index
    @MockitoBean AgoraMarketData marketData;                 // spin
    @MockitoBean SpinBalanceSheetSnapshotter balanceSheet;   // spin
    @MockitoBean SpinDistributionSnapshotter distribution;   // spin
    @MockitoBean SpinValuationSnapshotter valuation;         // spin

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        // ContainerConfig reuses the Postgres testcontainer across IT classes (withReuse(true)) —
        // other classes' index_event/spin_candidate rows would otherwise leak into this class's
        // lifecycle hunts and could route ENRICH through the (deliberately unstubbed) snapshotters.
        jdbc.sql("DELETE FROM index_event").update();
        jdbc.sql("DELETE FROM spin_candidate").update();

        when(earnings.recent(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));
        // Lazarus probes a fixed canary symbol before touching the watchlist; stubbed for EVERY
        // symbol (not just the canary) because other IT classes sharing the reused container may
        // have left watchlist rows behind for user "default" (StrigoiLazarusWebhookControllerIT
        // does the same for the same reason).
        when(companyData.fundamentals(anyString())).thenReturn(null);
        when(companyData.fundamentalsResult(anyString()))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(companyData.newsResult(any(), any(), any()))
                .thenReturn(DataSourceResult.healthy("agora", List.of(new NewsHeadline(
                        "SYNTHETIC headline", "SYNTHETIC summary", "synthetic-source", "rss",
                        Instant.parse("2026-01-02T00:00:00Z"), "https://example.com/1",
                        "example.com", 0.7))));
        when(filings.searchMergers(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(filings.recentForm4(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(filings.searchSpinoffs(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));
        when(reference.indexChanges(anyString(), anyInt()))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));
    }

    private HttpResponse<String> post(String path, String bearer, String rawBody) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(rawBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(rawBody));
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

    private String status(HttpResponse<String> r) {
        return healthOf(r).path("status").asText();
    }

    private String detail(HttpResponse<String> r) {
        return healthOf(r).path("detail").asText("");
    }

    // --- Step 1: derive the endpoint list and pin it ---

    @Test
    void derivedToolEndpointListMatchesTheSevenExpectedPaths() {
        var paths = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(i -> i.getPathPatternsCondition().getPatternValues().stream())
                .filter(p -> p.startsWith("/api/strigoi-") && p.contains("/tools/"))
                .toList();

        assertThat(paths).hasSize(7).containsExactlyInAnyOrderElementsOf(ALL_PATHS);
    }

    // --- Step 2: the matrix ---

    @ParameterizedTest
    @MethodSource("candidateEndpoints")
    void benignInputRunsNormally(String path) throws Exception {
        for (String body : List.of("{}", "{\"input\":{}}", "{\"input\":{\"lookback_days\":\"abc\"}}",
                "{\"input\":{},\"unknown\":1}")) {
            var out = post(path, TOKEN, body);
            assertThat(out.statusCode()).as("path=%s body=%s", path, body).isEqualTo(200);
            assertThat(status(out)).as("path=%s body=%s", path, body).isEqualTo("healthy");
            assertThat(detail(out)).as("path=%s body=%s", path, body).doesNotStartWith(GUARD_MARKER);
        }
    }

    @ParameterizedTest
    @MethodSource("candidateEndpoints")
    void absentBodyRunsNormally(String path) throws Exception {
        var out = post(path, TOKEN, null);
        assertThat(out.statusCode()).as("path=%s", path).isEqualTo(200);
        assertThat(status(out)).as("path=%s", path).isEqualTo("healthy");
        assertThat(detail(out)).as("path=%s", path).doesNotStartWith(GUARD_MARKER);
    }

    /** {@code fetch-news}'s own benign-input coverage: same "tolerant of noise" intent as
     *  {@link #benignInputRunsNormally}, but every body supplies the {@code symbol} this endpoint
     *  requires to run its lookup at all (see class javadoc). */
    @Test
    void fetchNewsBenignInputRunsNormally() throws Exception {
        String path = "/api/strigoi-echo/tools/fetch-news";
        for (String body : List.of(
                "{\"input\":{\"symbol\":\"AAPL\"}}",
                "{\"input\":{\"symbol\":\"AAPL\",\"since\":\"not-a-date\"}}",
                "{\"input\":{\"symbol\":\"AAPL\"},\"unknown\":1}",
                "{\"input\":{\"symbol\":\" AAPL \"}}")) {
            var out = post(path, TOKEN, body);
            assertThat(out.statusCode()).as("body=%s", body).isEqualTo(200);
            assertThat(status(out)).as("body=%s", body).isEqualTo("healthy");
            assertThat(detail(out)).as("body=%s", body).doesNotStartWith(GUARD_MARKER);
        }
    }

    /** {@code fetch-news}'s absent-body case is deliberately NOT folded into
     *  {@link #absentBodyRunsNormally}: without a body there is no {@code symbol}, and a missing
     *  symbol is the endpoint's own validation (design doc §3.3) — it degrades to the SAME
     *  200/unavailable/{@value #GUARD_MARKER} envelope the structural guard uses, by design, not
     *  because hunt() threw. Asserted here as the guarded shape it actually is, rather than
     *  forced into "healthy" and made to look like something it is not. */
    @Test
    void fetchNewsAbsentBodyIsGuardedByDesign() throws Exception {
        var out = post("/api/strigoi-echo/tools/fetch-news", TOKEN, null);
        assertThat(out.statusCode()).isEqualTo(200);
        assertThat(status(out)).isEqualTo("unavailable");
        assertThat(detail(out)).startsWith(GUARD_MARKER);
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void malformedBodyIsGuarded(String path) throws Exception {
        for (String body : List.of("[]", "{\"a\":")) {
            var out = post(path, TOKEN, body);
            assertThat(out.statusCode()).as("path=%s body=%s", path, body).isEqualTo(200);
            assertThat(status(out)).as("path=%s body=%s", path, body).isEqualTo("unavailable");
            assertThat(detail(out)).as("path=%s body=%s", path, body).startsWith(GUARD_MARKER);
        }
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void noTokenIs401(String path) throws Exception {
        var out = post(path, null, "{}");
        assertThat(out.statusCode()).as("path=%s", path).isEqualTo(401);
    }

    /** Anchor for the token re-check in {@code HuntController#unreadableBody}: a malformed body
     *  fails during argument resolution before the handler method (and its 401 check) ever runs,
     *  so the exception handler must repeat the check itself. Without it, an unauthenticated call
     *  with broken JSON would answer a clean 200. */
    @ParameterizedTest
    @MethodSource("endpoints")
    void noTokenPlusMalformedBodyIsStill401(String path) throws Exception {
        var out = post(path, null, "{\"a\":");
        assertThat(out.statusCode()).as("path=%s", path).isEqualTo(401);
    }

    /** Ledger follow-up (not in the original brief pseudocode, cheap to add here): the catch in
     *  {@code HuntController#handleFetch} sits OUTSIDE {@code cache.get} so a throw stores
     *  nothing — proven, not just read. A genuine {@code RuntimeException} from {@code hunt()}
     *  (as opposed to a source-reported {@code DataSourceResult.unavailable}) must not poison the
     *  cache entry for its {@code paramsKey}: the very next call for the same key must be free to
     *  run {@code hunt()} again and succeed, rather than replay a cached failure. */
    @Test
    void aThrowFromHuntStoresNothingInTheCache() throws Exception {
        String path = "/api/strigoi-echo/tools/fetch-candidates";
        // A lookback_days unused by any other test in this class, so its cache entry is fresh.
        String body = "{\"input\":{\"lookback_days\":29}}";
        when(earnings.recent(any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("SYNTHETIC upstream fault"));

        var guarded = post(path, TOKEN, body);
        assertThat(status(guarded)).isEqualTo("unavailable");
        assertThat(detail(guarded)).startsWith(GUARD_MARKER);

        when(earnings.recent(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));

        var recovered = post(path, TOKEN, body);
        assertThat(status(recovered)).isEqualTo("healthy");
        assertThat(detail(recovered)).doesNotStartWith(GUARD_MARKER);

        verify(earnings, times(2)).recent(any(LocalDate.class), any(LocalDate.class));
    }
}
