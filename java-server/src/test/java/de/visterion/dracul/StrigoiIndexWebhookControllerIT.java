package de.visterion.dracul;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.strigoi.index.enabled=true",
        "dracul.strigoi.index.webhook-token=test-index-token",
        "dracul.public-url=http://test.invalid:9090"
})
class StrigoiIndexWebhookControllerIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired de.visterion.dracul.agent.ToolFetchCache toolFetchCache;
    @MockitoBean AgoraReference reference;
    // The demand/drift snapshotters hit Agora; mock them so ENRICH is deterministic and offline
    // (parity with the spin IT). Defaults return empty snapshots; a test overrides per symbol.
    @MockitoBean de.visterion.dracul.strigoi.index.IndexDemandSnapshotter demandSnapshotter;
    @MockitoBean de.visterion.dracul.strigoi.index.IndexDriftSnapshotter driftSnapshotter;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build();
        // INGEST runs inside hunt() for every tracked index; default all to empty so a test only
        // needs to override the one index it seeds. RESPOND now reads the persisted lifecycle table
        // (findActiveUnpromoted), NOT the live constituents list, so constituents() is unstubbed.
        when(reference.indexChanges(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(DataSourceResult.healthy("agora", List.of()));
        // ENRICH default: empty per-stage snapshots (all fields null) so RESPOND base identity is
        // unaffected; a test overrides the demand snapshot for the symbol it cares about.
        when(demandSnapshotter.snapshot(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new de.visterion.dracul.strigoi.index.IndexDemandSnapshotter.IndexDemandSnapshot(
                        null, null, null, null, null, null, null, List.of(), false));
        when(driftSnapshotter.snapshot(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new de.visterion.dracul.strigoi.index.IndexDriftSnapshotter.IndexDriftSnapshot(
                        null, null, null, null, false));
        jdbc.sql("DELETE FROM index_event").update();
        // The ToolFetchCache bean is a context singleton shared across test methods and keys on
        // (toolName, lookback_days); clear it so tests reusing the same lookback are order-independent.
        toolFetchCache.clear();
    }

    @Test
    void huntIngestsAnnouncedChangesAsIndexEventRows() {
        when(reference.indexChanges(org.mockito.ArgumentMatchers.eq("sp500"),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(DataSourceResult.healthy("agora", List.of(
                        new de.visterion.dracul.hunting.agora.IndexChangeEvent(
                                "INGX", "", "sp500", "add",
                                LocalDate.now().minusDays(3), LocalDate.now().plusDays(4), "sp_press"),
                        // effective_date null -> must be skipped, not written
                        new de.visterion.dracul.hunting.agora.IndexChangeEvent(
                                "SKIP", "", "sp500", "add",
                                LocalDate.now().minusDays(3), null, "sp_press"))));

        rest.post().uri("/api/strigoi-index/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r-ingest", "tool_name", "fetch_index_reconstitution_events",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().toBodilessEntity();

        Integer ingx = jdbc.sql("SELECT count(*) FROM index_event WHERE symbol = 'INGX' AND status = 'ANNOUNCED'")
                .query(Integer.class).single();
        assertThat(ingx).as("announced change persisted").isEqualTo(1);
        Integer skip = jdbc.sql("SELECT count(*) FROM index_event WHERE symbol = 'SKIP'")
                .query(Integer.class).single();
        assertThat(skip).as("null effective_date skipped").isEqualTo(0);
    }

    @Test
    void huntSkipsNullAnnouncementDateVisiblyWithWarn() {
        ch.qos.logback.classic.Logger controllerLog =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        de.visterion.dracul.strigoi.index.StrigoiIndexWebhookController.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        controllerLog.addAppender(appender);
        try {
            when(reference.indexChanges(org.mockito.ArgumentMatchers.eq("sp500"),
                    org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(DataSourceResult.healthy("agora", List.of(
                            // valid effectiveDate but null announcementDate -> dropped visibly, no crash
                            new de.visterion.dracul.hunting.agora.IndexChangeEvent(
                                    "NOANN", "", "sp500", "add",
                                    null, LocalDate.now().plusDays(4), "sp_press"))));

            rest.post().uri("/api/strigoi-index/tools/fetch-candidates")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("run_id", "r-noann", "tool_name", "fetch_index_reconstitution_events",
                            "input", Map.of("lookback_days", 30)))
                    .retrieve().toBodilessEntity();   // must not crash

            Integer count = jdbc.sql("SELECT count(*) FROM index_event WHERE symbol = 'NOANN'")
                    .query(Integer.class).single();
            assertThat(count).as("null announcementDate not persisted").isEqualTo(0);

            boolean warned = appender.list.stream()
                    .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                            && e.getFormattedMessage().contains("missing announcementDate")
                            && e.getFormattedMessage().contains("NOANN"));
            assertThat(warned).as("visible WARN fired for the dropped row").isTrue();
        } finally {
            controllerLog.detachAppender(appender);
        }
    }

    @Test
    void respondBuildsCandidatesFromIngestedLifecycleRows() {
        // INGEST persists NEWO as an ANNOUNCED row; RESPOND (findActiveUnpromoted) reads it back
        // from the table — no live constituents list involved.
        when(reference.indexChanges(org.mockito.ArgumentMatchers.eq("sp500"),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(DataSourceResult.healthy("agora", List.of(
                        new de.visterion.dracul.hunting.agora.IndexChangeEvent(
                                "NEWO", "", "sp500", "add",
                                LocalDate.now().minusDays(5), LocalDate.now().plusDays(10), "sp_press"))));

        JsonNode resp = rest.post().uri("/api/strigoi-index/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r1", "tool_name", "fetch_index_reconstitution_events",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().body(JsonNode.class);

        JsonNode cands = resp.path("output").path("candidates");
        JsonNode newo = null;
        for (JsonNode c : cands) if ("NEWO".equals(c.path("symbol").asText())) newo = c;
        assertThat(newo).as("NEWO candidate returned from the lifecycle table").isNotNull();
        assertThat(newo.path("status").asText()).isEqualTo("ANNOUNCED");
        assertThat(newo.path("effectiveDate").asText())
                .isEqualTo(LocalDate.now().plusDays(10).toString());
    }

    @Test
    void enrichPersistsDemandSnapshotAndItSurfacesInThePayload() {
        // A seeded ANNOUNCED row is picked up by ENRICH; the (mocked) demand snapshot is persisted to
        // announced_snapshot and read back by RESPOND into the wire payload.
        insertRow("DEM", "add", LocalDate.now().minusDays(3), LocalDate.now().plusDays(6),
                "ANNOUNCED", false);
        when(demandSnapshotter.snapshot(org.mockito.ArgumentMatchers.eq("DEM"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new de.visterion.dracul.strigoi.index.IndexDemandSnapshotter.IndexDemandSnapshot(
                        java.math.BigDecimal.valueOf(100000), 6000.0, 1000L, 0.02, 5000.0, 11500.0,
                        11500.0, List.of("dilution"), true));

        JsonNode resp = rest.post().uri("/api/strigoi-index/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r-enrich", "tool_name", "fetch_index_reconstitution_events",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().body(JsonNode.class);

        JsonNode dem = null;
        for (JsonNode c : resp.path("output").path("candidates"))
            if ("DEM".equals(c.path("symbol").asText())) dem = c;
        assertThat(dem).as("DEM surfaces with its demand snapshot").isNotNull();
        assertThat(dem.path("adv").asDouble()).isEqualTo(100000.0);
        assertThat(dem.path("marketCap").asDouble()).isEqualTo(6000.0);
        assertThat(dem.path("avgVolume20d").asLong()).isEqualTo(1000L);
        assertThat(dem.path("idiosyncraticVol").asDouble()).isEqualTo(0.02);
        assertThat(dem.path("freeFloatProxyMillions").asDouble()).isEqualTo(5000.0);
        assertThat(dem.path("demandToAdvRatioEstimate").asDouble()).isEqualTo(11500.0);
        assertThat(dem.path("confounders").get(0).asText()).isEqualTo("dilution");

        Integer stored = jdbc.sql(
                "SELECT count(*) FROM index_event WHERE symbol = 'DEM' AND announced_snapshot IS NOT NULL")
                .query(Integer.class).single();
        assertThat(stored).as("announced_snapshot persisted").isEqualTo(1);
    }

    @Test
    void respondExcludesTerminalAndPromotedRows() {
        insertRow("ACT", "add", LocalDate.now().minusDays(3), LocalDate.now().plusDays(6),
                "ANNOUNCED", false);
        insertRow("CLO", "add", LocalDate.now().minusDays(90), LocalDate.now().minusDays(60),
                "CLOSED", false);
        insertRow("PRM", "add", LocalDate.now().minusDays(3), LocalDate.now().plusDays(6),
                "ANNOUNCED", true);

        JsonNode resp = rest.post().uri("/api/strigoi-index/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r-active", "tool_name", "fetch_index_reconstitution_events",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().body(JsonNode.class);

        List<String> symbols = new java.util.ArrayList<>();
        for (JsonNode c : resp.path("output").path("candidates")) symbols.add(c.path("symbol").asText());
        assertThat(symbols).contains("ACT").doesNotContain("CLO", "PRM");
    }

    @Test
    void reconcileAdvancesEffectiveDatedRowAndItStillSurfaces() {
        // an ANNOUNCED row whose effective date is today -> RECONCILE moves it to EFFECTIVE, and
        // EFFECTIVE is still part of the active-unpromoted window, so it stays in the payload.
        insertRow("EFF", "add", LocalDate.now().minusDays(10), LocalDate.now(), "ANNOUNCED", false);

        JsonNode resp = rest.post().uri("/api/strigoi-index/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r-reconcile", "tool_name", "fetch_index_reconstitution_events",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().body(JsonNode.class);

        JsonNode eff = null;
        for (JsonNode c : resp.path("output").path("candidates"))
            if ("EFF".equals(c.path("symbol").asText())) eff = c;
        assertThat(eff).as("EFF still surfaces after the EFFECTIVE transition").isNotNull();
        assertThat(eff.path("status").asText()).isEqualTo("EFFECTIVE");

        String status = jdbc.sql("SELECT status FROM index_event WHERE symbol = 'EFF'")
                .query(String.class).single();
        assertThat(status).isEqualTo("EFFECTIVE");
        Integer stamped = jdbc.sql("SELECT count(*) FROM index_event WHERE symbol = 'EFF' AND effective_at IS NOT NULL")
                .query(Integer.class).single();
        assertThat(stamped).as("effective_at stamped on the transition").isEqualTo(1);
    }

    /** Inserts a lifecycle row directly (bypassing INGEST) for RESPOND/reconcile assertions. */
    private void insertRow(String symbol, String action, LocalDate announcement,
                           LocalDate effective, String status, boolean promoted) {
        jdbc.sql("""
                INSERT INTO index_event
                  (symbol, index_name, action, source, announcement_date, effective_date, status, promoted_at)
                VALUES
                  (:symbol, 'sp500', :action, 'sp_press', :ann::date, :eff::date, :status,
                   CASE WHEN :promoted THEN now() ELSE NULL END)
                """)
                .param("symbol", symbol)
                .param("action", action)
                .param("ann", announcement.toString())
                .param("eff", effective.toString())
                .param("status", status)
                .param("promoted", promoted)
                .update();
    }

    @Test
    void toolEndpointReturns401WithoutBearer() {
        try {
            rest.post().uri("/api/strigoi-index/tools/fetch-candidates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("input", Map.of())).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            return;
        }
        fail("Expected 401");
    }

    @Test
    void completeEndpointPersistsIndexPrey() {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", "NEWO", "companyName", "NewCo",
                        "anomalyType", "INDEX_INCLUSION", "confidence", 0.66,
                        "thesis", "Added to S&P 500 last week; index-fund demand still building.",
                        "signals", List.of("Recent S&P 500 addition"),
                        "risks", List.of("Drift may already be priced"),
                        "horizon", "1m")));
        var resp = rest.post().uri("/api/strigoi-index/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .header("X-Vistierie-Run-Id", "run-index-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-index-1", "status", "done", "output", preyJson))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode chronicle = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        boolean found = false;
        for (JsonNode p : chronicle.path("prey")) {
            if ("NEWO".equals(p.path("symbol").asText())
                    && "strigoi-index".equals(p.path("discoveredBy").asText())
                    && "INDEX_INCLUSION".equals(p.path("anomalyType").asText())) found = true;
        }
        assertThat(found).as("INDEX_INCLUSION prey persisted + visible").isTrue();
    }

    @Test
    void completeEndpointSkipsBlankSymbolPrey() {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", "", "companyName", "BlankCo Index",
                        "anomalyType", "INDEX_INCLUSION", "confidence", 0.5,
                        "thesis", "No ticker.", "signals", List.of(), "risks", List.of(),
                        "horizon", "1m")));
        rest.post().uri("/api/strigoi-index/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .header("X-Vistierie-Run-Id", "run-index-2")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-index-2", "status", "done", "output", preyJson))
                .retrieve().toBodilessEntity();

        JsonNode chronicle = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        for (JsonNode p : chronicle.path("prey")) {
            assertThat(p.path("companyName").asText()).isNotEqualTo("BlankCo Index");
        }
    }

    @Test
    void completeEndpointReturns401WithWrongBearer() {
        try {
            rest.post().uri("/api/strigoi-index/complete")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer wrong")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", "done", "output", Map.of("prey", List.of())))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            return;
        }
        fail("Expected 401");
    }

    @Test
    void unavailableSourceSurfacesAndIsNotCached() {
        // The sp500 ingest fetch (the verified-live anchor) is down; its health rides RESPOND.
        org.mockito.Mockito.when(reference.indexChanges(
                        org.mockito.ArgumentMatchers.eq("sp500"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(DataSourceResult.unavailable("agora", "agora: 503"));

        JsonNode resp = rest.post().uri("/api/strigoi-index/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r-unavail", "tool_name", "fetch_index_reconstitution_events",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().body(JsonNode.class);

        assertThat(resp.path("output").path("data_source_health").path("status").asText())
                .isEqualTo("unavailable");

        // second identical call — must NOT be served from cache (unhealthy payloads are not cached)
        rest.post().uri("/api/strigoi-index/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r-unavail-2", "tool_name", "fetch_index_reconstitution_events",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().body(JsonNode.class);

        org.mockito.Mockito.verify(reference, org.mockito.Mockito.times(2))
                .indexChanges(org.mockito.ArgumentMatchers.eq("sp500"), org.mockito.ArgumentMatchers.anyInt());
    }

    // --- Promotion (event -> prey), afterPersist hook (G6) ---

    /** Inserts a lifecycle row with an explicit source, returning its id (for promotion assertions). */
    private long insertRowWithSource(String symbol, String source, LocalDate announcement,
                                     LocalDate effective, String status) {
        return jdbc.sql("""
                INSERT INTO index_event
                  (symbol, index_name, action, source, announcement_date, effective_date, status)
                VALUES
                  (:symbol, 'sp500', 'add', :source, :ann::date, :eff::date, :status)
                RETURNING id
                """)
                .param("symbol", symbol)
                .param("source", source)
                .param("ann", announcement.toString())
                .param("eff", effective.toString())
                .param("status", status)
                .query(Long.class).single();
    }

    private void postIndexPrey(String runId, String symbol) {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", symbol, "companyName", symbol + " Inc",
                        "anomalyType", "INDEX_INCLUSION", "confidence", 0.7,
                        "thesis", "Announced S&P 500 addition; forced-buy window still open.",
                        "signals", List.of("Pending S&P 500 addition"),
                        "risks", List.of("May be front-run"),
                        "kill_criteria", List.of("Change cancelled before the effective date"),
                        "horizon", "1m")));
        rest.post().uri("/api/strigoi-index/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-index-token")
                .header("X-Vistierie-Run-Id", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", runId, "status", "done", "output", preyJson))
                .retrieve().toBodilessEntity();
    }

    @Test
    void completePromotesAnnouncedEventWithinWindowAndLinksPreyId() {
        // ANNOUNCED sp_press row, effective 3 days out (inside the default 5-day S&P window).
        long id = insertRowWithSource("PROM", "sp_press",
                LocalDate.now().minusDays(2), LocalDate.now().plusDays(3), "ANNOUNCED");

        postIndexPrey("run-promo-1", "PROM");

        String preyId = jdbc.sql(
                "SELECT id::text FROM prey WHERE symbol = 'PROM' AND discovered_by = 'strigoi-index'")
                .query(String.class).single();

        var row = jdbc.sql("SELECT promoted_at, promoted_prey_id FROM index_event WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> Map.of(
                        "promoted_at", String.valueOf(rs.getString("promoted_at")),
                        "promoted_prey_id", String.valueOf(rs.getString("promoted_prey_id"))))
                .single();
        assertThat(row.get("promoted_at")).as("promoted_at stamped").isNotEqualTo("null");
        assertThat(row.get("promoted_prey_id")).as("prey id linked").isEqualTo(preyId);
    }

    @Test
    void effectiveAndPostEventsAreNeverPromoted() {
        // EFFECTIVE / POST rows are past the forced-trade window: even when a prey hits their symbol,
        // findPromotableBySymbol (ANNOUNCED-only) never returns them, so they can never be promoted.
        insertRowWithSource("EFFX", "sp_press",
                LocalDate.now().minusDays(10), LocalDate.now(), "EFFECTIVE");
        insertRowWithSource("POSX", "sp_press",
                LocalDate.now().minusDays(20), LocalDate.now().minusDays(8), "POST");

        postIndexPrey("run-eff-1", "EFFX");
        postIndexPrey("run-pos-1", "POSX");

        Integer promoted = jdbc.sql(
                "SELECT count(*) FROM index_event WHERE symbol IN ('EFFX','POSX') AND promoted_at IS NOT NULL")
                .query(Integer.class).single();
        assertThat(promoted).as("EFFECTIVE/POST never promoted").isEqualTo(0);
    }

    @Test
    void russellSourceUsesTheWiderPromotionWindow() {
        // effective 15 days out: inside the Russell 20-day window, but OUTSIDE the S&P 5-day window.
        // Proves windowForSource actually branches on source — a regression that always applied the
        // S&P window (5) would leave the Russell row unpromoted.
        long russellId = insertRowWithSource("RUSS", "russell_reconstitution",
                LocalDate.now().minusDays(2), LocalDate.now().plusDays(15), "ANNOUNCED");
        // Same 15-day horizon under the S&P source: control that MUST NOT promote (15 > 5).
        long spId = insertRowWithSource("SPWX", "sp_press",
                LocalDate.now().minusDays(2), LocalDate.now().plusDays(15), "ANNOUNCED");

        postIndexPrey("run-russ-1", "RUSS");
        postIndexPrey("run-spwx-1", "SPWX");

        String russellPromotedAt = jdbc.sql("SELECT promoted_at FROM index_event WHERE id = :id")
                .param("id", russellId).query(String.class).optional().orElse(null);
        assertThat(russellPromotedAt).as("Russell row promoted (15 within the 20-day window)").isNotNull();

        String spPromotedAt = jdbc.sql("SELECT promoted_at FROM index_event WHERE id = :id")
                .param("id", spId).query(String.class).optional().orElse(null);
        assertThat(spPromotedAt).as("S&P row not promoted (15 outside the 5-day window)").isNull();
    }

    @Test
    void announcedEventOutsideWindowIsNotPromoted() {
        // ANNOUNCED sp_press row, effective 30 days out — well outside the 5-day S&P window.
        long id = insertRowWithSource("FARX", "sp_press",
                LocalDate.now().minusDays(2), LocalDate.now().plusDays(30), "ANNOUNCED");

        postIndexPrey("run-far-1", "FARX");

        String promotedAt = jdbc.sql("SELECT promoted_at FROM index_event WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse(null);
        assertThat(promotedAt).as("out-of-window ANNOUNCED not promoted").isNull();
        // The prey itself still persists (fail-soft skip is about promotion only).
        Integer preyCount = jdbc.sql(
                "SELECT count(*) FROM prey WHERE symbol = 'FARX' AND discovered_by = 'strigoi-index'")
                .query(Integer.class).single();
        assertThat(preyCount).as("prey persisted despite no promotion").isEqualTo(1);
    }

    @Test
    void duplicateDeliveryDoesNotRePromote() {
        long id = insertRowWithSource("DUPX", "sp_press",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(4), "ANNOUNCED");

        postIndexPrey("run-dupx-1", "DUPX");
        String firstPromotedAt = jdbc.sql("SELECT promoted_at FROM index_event WHERE id = :id")
                .param("id", id).query(String.class).single();
        String firstPreyId = jdbc.sql("SELECT promoted_prey_id FROM index_event WHERE id = :id")
                .param("id", id).query(String.class).single();
        assertThat(firstPromotedAt).isNotNull();

        // Second identical delivery — the V21 prey natural-key backstop skips the insert, so
        // afterPersist never fires again and the promotion stamp is untouched.
        postIndexPrey("run-dupx-1", "DUPX");

        Integer preyCount = jdbc.sql(
                "SELECT count(*) FROM prey WHERE symbol = 'DUPX' AND discovered_by = 'strigoi-index'")
                .query(Integer.class).single();
        assertThat(preyCount).as("no double prey (V21 backstop)").isEqualTo(1);

        String secondPromotedAt = jdbc.sql("SELECT promoted_at FROM index_event WHERE id = :id")
                .param("id", id).query(String.class).single();
        String secondPreyId = jdbc.sql("SELECT promoted_prey_id FROM index_event WHERE id = :id")
                .param("id", id).query(String.class).single();
        assertThat(secondPromotedAt).as("promoted_at not re-stamped").isEqualTo(firstPromotedAt);
        assertThat(secondPreyId).as("prey id not re-linked").isEqualTo(firstPreyId);
    }
}
