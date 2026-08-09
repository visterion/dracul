package de.visterion.dracul;

import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import de.visterion.dracul.hunting.agora.FilingText;
import de.visterion.dracul.hunting.agora.SpinoffFiling;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.strigoi.spin.SpinBalanceSheetSnapshotter;
import de.visterion.dracul.strigoi.spin.SpinBalanceSheetSnapshotter.SpinBalanceSheetSnapshot;
import de.visterion.dracul.strigoi.spin.SpinDistributionSnapshotter;
import de.visterion.dracul.strigoi.spin.SpinDistributionSnapshotter.SpinDistributionSnapshot;
import de.visterion.dracul.strigoi.spin.SpinValuationSnapshotter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/** End-to-end 4-phase hunt (INGEST -> RECONCILE -> ENRICH -> RESPOND) over a real DB, real
 *  reconciler + enricher, with the Agora-touching facades and the stage snapshotters mocked. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.strigoi.spin.enabled=true",
        "dracul.strigoi.spin.webhook-token=test-spin-token",
        "dracul.public-url=http://test.invalid:9090"
})
class StrigoiSpinWebhookControllerIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired JdbcClient jdbc;
    @MockitoBean AgoraFilings filings;
    @MockitoBean AgoraMarketData marketData;
    @MockitoBean SpinBalanceSheetSnapshotter balanceSheet;
    @MockitoBean SpinDistributionSnapshotter distribution;
    @MockitoBean SpinValuationSnapshotter valuation;

    RestClient rest;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM spin_candidate").update();
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build();

        // INGEST: one soon-to-trade spin-co (SPN) and one still-registered spin-co (REG).
        when(filings.searchSpinoffs(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.healthy("agora", List.of(
                        new SpinoffFiling("SPN", "SpinCo Inc", "10-12B",
                                LocalDate.now().minusDays(5), "http://sec/spn", "0000000901"),
                        new SpinoffFiling("REG", "Registered Co", "10-12B",
                                LocalDate.now().minusDays(3), "http://sec/reg", "0000000902"))));

        // RECONCILE: SPN resolves to a positive quote -> DISTRIBUTED; REG has no quote -> REGISTERED.
        when(marketData.quotes(any()))
                .thenReturn(Map.of("SPN", new Quote(new BigDecimal("12.50"), BigDecimal.ZERO)));

        // ENRICH: default term-sheet fetch unavailable; REG's is available with a parent ticker so
        // the raw prose + best-effort parent are persisted (Fix 1/2). Settlement probe: no facts.
        when(filings.filingText(any())).thenReturn(FilingText.unavailable());
        when(filings.filingText(eq("http://sec/reg"))).thenReturn(new FilingText(
                "Registered Co will be separated from Big Parent Corporation (NYSE: BPC).", true));
        when(filings.conceptStrict(any(), any(), eq("Assets"))).thenReturn(ConceptSeries.empty("Assets"));
        when(balanceSheet.snapshot(any(), any())).thenReturn(
                new SpinBalanceSheetSnapshot(new BigDecimal("5000"), new BigDecimal("2000"),
                        new BigDecimal("1000"), "Industrials", true));
        when(distribution.snapshot(any(), any(), any(), anyBoolean(), any())).thenReturn(
                new SpinDistributionSnapshot(150.0, 3000.0, 0.05, 4, true, false, true, true));
    }

    private JsonNode fetch(String runId) {
        return rest.post().uri("/api/strigoi-spin/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-spin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", runId, "tool_name", "fetch_recent_spinoff_candidates",
                        "input", Map.of("lookback_days", 30)))
                .retrieve().body(JsonNode.class);
    }

    private static JsonNode bySymbol(JsonNode candidates, String symbol) {
        for (JsonNode c : candidates) if (symbol.equals(c.path("symbol").asString(""))) return c;
        return null;
    }

    @Test
    void fourPhaseHuntPersistsReconcilesAndReturnsEnrichedCandidates() {
        JsonNode cands = fetch("r1").path("output").path("candidates");

        JsonNode spn = bySymbol(cands, "SPN");
        assertThat(spn).as("SPN candidate returned").isNotNull();
        assertThat(spn.path("status").asString("")).isEqualTo("DISTRIBUTED");
        assertThat(spn.path("spincoMarketCapMillions").asDouble()).isEqualTo(150.0);
        assertThat(spn.path("sizeRatio").asDouble()).isEqualTo(0.05);
        assertThat(spn.path("daysSinceDistribution").asInt()).isEqualTo(4);
        assertThat(spn.path("distributionDateConfirmed").asBoolean()).isTrue();

        JsonNode reg = bySymbol(cands, "REG");
        assertThat(reg).as("REG candidate returned").isNotNull();
        assertThat(reg.path("status").asString("")).isEqualTo("REGISTERED");
        assertThat(reg.path("totalAssets").asDouble()).isEqualTo(5000.0);
        assertThat(reg.path("industry").asString("")).isEqualTo("Industrials");
        // Fix 1: raw term-sheet prose flows to the payload and termSheetAvailable is truthful
        assertThat(reg.path("termSheetAvailable").asBoolean()).isTrue();
        assertThat(reg.path("termSheet").asString("")).contains("Big Parent Corporation");
        // REG never reached DISTRIBUTED, so its distributed_snapshot is null — distributionDateConfirmed
        // must still come back as a concrete `false`, never a missing key or a null.
        assertThat(reg.has("distributionDateConfirmed")).isTrue();
        assertThat(reg.path("distributionDateConfirmed").asBoolean(true)).isFalse();

        // lifecycle persisted: SPN advanced to DISTRIBUTED in the DB
        String spnStatus = jdbc.sql("SELECT status FROM spin_candidate WHERE symbol = 'SPN'")
                .query(String.class).single();
        assertThat(spnStatus).isEqualTo("DISTRIBUTED");
        // Fix 2: best-effort parent ticker extracted from REG's term sheet + persisted
        String parent = jdbc.sql("SELECT parent_symbol FROM spin_candidate WHERE symbol = 'REG'")
                .query(String.class).single();
        assertThat(parent).isEqualTo("BPC");

        JsonNode health = fetch("r1").path("output").path("data_source_health");
        assertThat(health.path("status").asString("")).isEqualTo("healthy");
        assertThat(health.path("source").asString("")).isEqualTo("agora");
    }

    /**
     * 2026-08-08 spin-ticker-backfill, end-to-end regression: a row planted long before the
     * lookback window, with no symbol (the historic Agora EFTS bug this fix targets), no term
     * sheet, and therefore no {@code distribution_date}. Re-reading the same filing today backfills
     * the ticker (change 1), which lets the quote probe move it to DISTRIBUTED (stamping
     * {@code distributed_at = now()}), which change 3 is what keeps it in the RESPOND payload
     * despite the old filing date and the still-NULL distribution date. Asserting only the DB row
     * would prove nothing about change 3 — the payload is the actual regression guard.
     */
    @Test
    void backfilledSymbolReachesDistributedAndSurvivesTheWindowViaDistributedAt() {
        jdbc.sql("""
                INSERT INTO spin_candidate (cik, symbol, company_name, form_type, filing_date, filing_url, status)
                VALUES ('0000009999', NULL, 'Healed Spinco', '10-12B', :filed, 'http://sec/healed', 'REGISTERED')
                """)
                .param("filed", LocalDate.now().minusDays(200))
                .update();

        // Re-reading the SAME filing, Agora now resolves the ticker (the fix this backfill targets).
        when(filings.searchSpinoffs(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(DataSourceResult.healthy("agora", List.of(
                        new SpinoffFiling("HEAL", "Healed Spinco", "10-12B",
                                LocalDate.now().minusDays(200), "http://sec/healed", "0000009999"))));
        // Quote resolves -> the price probe moves it to DISTRIBUTED this same run.
        when(marketData.quotes(any()))
                .thenReturn(Map.of("HEAL", new Quote(new BigDecimal("27.04"), BigDecimal.ZERO)));

        // A lookback_days distinct from every other test in this class (30): the ToolFetchCache key
        // is (toolName, lookback_days), shared across test methods within the one Spring context, so
        // reusing 30 here would silently serve back an earlier test's cached SPN/REG payload instead
        // of actually invoking hunt() for this scenario.
        JsonNode cands = rest.post().uri("/api/strigoi-spin/tools/fetch-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-spin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "r-healed", "tool_name", "fetch_recent_spinoff_candidates",
                        "input", Map.of("lookback_days", 31)))
                .retrieve().body(JsonNode.class)
                .path("output").path("candidates");

        JsonNode heal = bySymbol(cands, "HEAL");
        assertThat(heal).as("backfilled + freshly-distributed candidate reaches the payload").isNotNull();
        assertThat(heal.path("status").asString("")).isEqualTo("DISTRIBUTED");
        // No term-sheet distributionDate was ever parsed for HEAL, so the enricher must tell the
        // snapshotter that its effective distribution date is the distributed_at fallback (the
        // detection stamp), not a confirmed term-sheet date — see SpinCandidateEnricher#enrichDistributed.
        org.mockito.Mockito.verify(distribution).snapshot(eq("HEAL"), any(), any(), eq(false), any());

        var row = jdbc.sql("""
                        SELECT symbol, status, filing_date, distribution_date
                          FROM spin_candidate WHERE cik = '0000009999'
                        """)
                .query((rs, n) -> Map.of(
                        "symbol", String.valueOf(rs.getString("symbol")),
                        "status", rs.getString("status"),
                        "filingDate", rs.getObject("filing_date", LocalDate.class),
                        "distributionDate", String.valueOf(rs.getObject("distribution_date", LocalDate.class))))
                .single();
        assertThat(row.get("symbol")).isEqualTo("HEAL");
        assertThat(row.get("status")).isEqualTo("DISTRIBUTED");
        assertThat((LocalDate) row.get("filingDate")).isBefore(LocalDate.now().minusDays(30));
        assertThat(row.get("distributionDate")).as("no term sheet date was ever parsed").isEqualTo("null");
    }

    @Test
    void toolEndpointReturns401WithoutBearer() {
        try {
            rest.post().uri("/api/strigoi-spin/tools/fetch-candidates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("input", Map.of())).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            return;
        }
        fail("Expected 401");
    }

    @Test
    void completeEndpointPersistsSpinoffPrey() {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", "SPNX", "companyName", "Spinco X",
                        "anomalyType", "SPINOFF", "confidence", 0.7,
                        "thesis", "Forced index selling post-separation.",
                        "signals", List.of("Dropped from parent's index"),
                        "risks", List.of("Thin float"),
                        "horizon", "6m")));
        var resp = rest.post().uri("/api/strigoi-spin/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-spin-token")
                .header("X-Vistierie-Run-Id", "run-spin-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-spin-1", "status", "done", "output", preyJson))
                .retrieve().toBodilessEntity();
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode chronicle = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        boolean found = false;
        for (JsonNode p : chronicle.path("prey")) {
            if ("SPNX".equals(p.path("symbol").asText())
                    && "strigoi-spin".equals(p.path("discoveredBy").asText())
                    && "SPINOFF".equals(p.path("anomalyType").asText())) found = true;
        }
        assertThat(found).as("SPINOFF prey persisted + visible").isTrue();
    }

    @Test
    void completeEndpointSkipsBlankSymbolPrey() {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", "", "companyName", "BlankCo Spinco",
                        "anomalyType", "SPINOFF", "confidence", 0.6,
                        "thesis", "Not trading yet.", "signals", List.of(), "risks", List.of(),
                        "horizon", "6m")));
        rest.post().uri("/api/strigoi-spin/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-spin-token")
                .header("X-Vistierie-Run-Id", "run-spin-2")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", "run-spin-2", "status", "done", "output", preyJson))
                .retrieve().toBodilessEntity();

        JsonNode chronicle = rest.get().uri("/api/chronicle?includeDismissed=true")
                .retrieve().body(JsonNode.class);
        for (JsonNode p : chronicle.path("prey")) {
            assertThat(p.path("companyName").asText()).isNotEqualTo("BlankCo Spinco");
        }
    }

    // --- Promotion (candidate -> prey), afterPersist hook (E4) ---

    /** Inserts a DISTRIBUTED, unpromoted candidate with the given distribution snapshot JSON. */
    private long insertDistributed(String symbol, String snapshotJson) {
        return jdbc.sql("""
                INSERT INTO spin_candidate (symbol, company_name, status, distributed_at, distributed_snapshot)
                VALUES (:symbol, :name, 'DISTRIBUTED', now(), :snap::jsonb)
                RETURNING id
                """)
                .param("symbol", symbol)
                .param("name", symbol + " Spinco")
                .param("snap", snapshotJson)
                .query(Long.class).single();
    }

    private void postSpinoffPrey(String runId, String symbol) {
        var preyJson = Map.of("prey", List.of(
                Map.of("symbol", symbol, "companyName", symbol + " Spinco",
                        "anomalyType", "SPINOFF", "confidence", 0.7,
                        "thesis", "Forced index selling post-separation.",
                        "signals", List.of("Dropped from parent's index"),
                        "risks", List.of("Thin float"),
                        "kill_criteria", List.of("No separation by 2026-12-31"),
                        "horizon", "3m")));
        rest.post().uri("/api/strigoi-spin/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-spin-token")
                .header("X-Vistierie-Run-Id", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("run_id", runId, "status", "done", "output", preyJson))
                .retrieve().toBodilessEntity();
    }

    @Test
    void completePromotesDistributedCandidateAndLinksPreyId() {
        // sizeRatio deliberately null — the relaxed gate promotes on spincoMarketCap + window alone.
        // distributionDateConfirmed:true — a row with a confirmed date inside the window is
        // promotable exactly as before the gate change. A gate that requires the flag but reads it
        // from the wrong layer (e.g. always false) would fail this test.
        long id = insertDistributed("PROMO",
                "{\"spincoMarketCapMillions\":150.0,\"sizeRatio\":null,\"daysSinceDistribution\":5,"
                        + "\"distributionDateConfirmed\":true}");

        postSpinoffPrey("run-promo-1", "PROMO");

        String preyId = jdbc.sql(
                "SELECT id::text FROM prey WHERE symbol = 'PROMO' AND discovered_by = 'strigoi-spin'")
                .query(String.class).single();

        var row = jdbc.sql(
                "SELECT promoted_at, promoted_prey_id FROM spin_candidate WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> Map.of(
                        "promoted_at", String.valueOf(rs.getString("promoted_at")),
                        "promoted_prey_id", String.valueOf(rs.getString("promoted_prey_id"))))
                .single();
        assertThat(row.get("promoted_at")).as("promoted_at stamped").isNotEqualTo("null");
        assertThat(row.get("promoted_prey_id")).as("prey id linked").isEqualTo(preyId);
    }

    @Test
    void duplicateDeliveryDoesNotRePromoteNorDoublePersist() {
        long id = insertDistributed("DUP",
                "{\"spincoMarketCapMillions\":120.0,\"sizeRatio\":0.04,\"daysSinceDistribution\":3,"
                        + "\"distributionDateConfirmed\":true}");

        postSpinoffPrey("run-dup-1", "DUP");
        String firstPromotedAt = jdbc.sql("SELECT promoted_at FROM spin_candidate WHERE id = :id")
                .param("id", id).query(String.class).single();
        String firstPreyId = jdbc.sql(
                "SELECT promoted_prey_id FROM spin_candidate WHERE id = :id")
                .param("id", id).query(String.class).single();
        assertThat(firstPromotedAt).isNotNull();

        // Second identical delivery — V21 prey natural-key backstop skips the insert, so afterPersist
        // never fires again and the promotion stamp is untouched.
        postSpinoffPrey("run-dup-1", "DUP");

        Integer preyCount = jdbc.sql(
                "SELECT count(*) FROM prey WHERE symbol = 'DUP' AND discovered_by = 'strigoi-spin'")
                .query(Integer.class).single();
        assertThat(preyCount).as("no double prey (V21 backstop)").isEqualTo(1);

        String secondPromotedAt = jdbc.sql("SELECT promoted_at FROM spin_candidate WHERE id = :id")
                .param("id", id).query(String.class).single();
        String secondPreyId = jdbc.sql(
                "SELECT promoted_prey_id FROM spin_candidate WHERE id = :id")
                .param("id", id).query(String.class).single();
        assertThat(secondPromotedAt).as("promoted_at not re-stamped").isEqualTo(firstPromotedAt);
        assertThat(secondPreyId).as("prey id not re-linked").isEqualTo(firstPreyId);
    }

    @Test
    void preyWithoutMatchingCandidateIsFailSoft() {
        // No spin_candidate row for NOMATCH — promotion is skipped, the prey still persists.
        postSpinoffPrey("run-nomatch-1", "NOMATCH");

        Integer preyCount = jdbc.sql(
                "SELECT count(*) FROM prey WHERE symbol = 'NOMATCH' AND discovered_by = 'strigoi-spin'")
                .query(Integer.class).single();
        assertThat(preyCount).as("prey persisted despite no candidate match").isEqualTo(1);

        Integer promotedCount = jdbc.sql(
                "SELECT count(*) FROM spin_candidate WHERE promoted_at IS NOT NULL")
                .query(Integer.class).single();
        assertThat(promotedCount).as("nothing promoted").isEqualTo(0);
    }

    /** Confirmed date, outside the (default 90-day) window: not promotable. Regression guard for a
     *  gate that stopped checking {@code daysSinceDistribution} against {@code promotionWindowDays}
     *  at all, or that inverted the comparison. */
    @Test
    void confirmedDateOutsideWindowIsNotPromoted() {
        long id = insertDistributed("STALE",
                "{\"spincoMarketCapMillions\":200.0,\"sizeRatio\":0.03,\"daysSinceDistribution\":120,"
                        + "\"distributionDateConfirmed\":true}");

        postSpinoffPrey("run-stale-1", "STALE");

        var promotedAt = jdbc.sql("SELECT promoted_at::text FROM spin_candidate WHERE id = :id")
                .param("id", id).query(String.class).optional();
        assertThat(promotedAt).as("120 days out of a 90-day window must not promote").isEmpty();
    }

    /**
     * The regression this whole change targets: an UNCONFIRMED distribution date reading
     * {@code daysSinceDistribution = 0} — exactly the shape the 2026-08-08 ticker backfill produced
     * for HONA/BSEM/ADIG/MBGL/MFP (real filings from 2026-05-27..2026-07-24, all stamped
     * {@code distributed_at = 2026-08-08}). A gate that only checks
     * {@code daysSinceDistribution <= promotionWindowDays} without requiring
     * {@code distributionDateConfirmed} would promote this row (0 <= 90) even though the real
     * forced-selling window closed months ago.
     */
    @Test
    void unconfirmedZeroDaysIsNotPromoted() {
        long id = insertDistributed("BACKFILL",
                "{\"spincoMarketCapMillions\":90.0,\"sizeRatio\":0.02,\"daysSinceDistribution\":0,"
                        + "\"distributionDateConfirmed\":false}");

        postSpinoffPrey("run-backfill-1", "BACKFILL");

        var promotedAt = jdbc.sql("SELECT promoted_at::text FROM spin_candidate WHERE id = :id")
                .param("id", id).query(String.class).optional();
        assertThat(promotedAt)
                .as("unconfirmed daysSinceDistribution=0 (the backfill shape) must not promote")
                .isEmpty();
    }

    /**
     * Same as {@link #unconfirmedZeroDaysIsNotPromoted}, but with the flag entirely absent from the
     * snapshot JSON rather than explicitly {@code false} — the shape older/backfilled rows that never
     * carried the field would actually have on disk. Regression guard for a gate implemented as
     * {@code dist.path("distributionDateConfirmed").asBoolean()} (defaults to {@code false} for a
     * missing key too, so this should behave identically to the explicit-false case) versus one that
     * mis-reads a missing key as confirmed.
     */
    @Test
    void missingConfirmedFlagIsNotPromoted() {
        long id = insertDistributed("NOFLAG",
                "{\"spincoMarketCapMillions\":90.0,\"sizeRatio\":0.02,\"daysSinceDistribution\":0}");

        postSpinoffPrey("run-noflag-1", "NOFLAG");

        var promotedAt = jdbc.sql("SELECT promoted_at::text FROM spin_candidate WHERE id = :id")
                .param("id", id).query(String.class).optional();
        assertThat(promotedAt).as("missing distributionDateConfirmed key must not promote").isEmpty();
    }

    /** No {@code daysSinceDistribution} at all (even with a confirmed flag true): unchanged from
     *  today's behaviour — {@code days.isNumber()} is false, so the gate fails regardless of the new
     *  confirmed check. Regression guard against accidentally loosening the missing-days case while
     *  adding the confirmed check. */
    @Test
    void missingDaysSinceDistributionIsNotPromoted() {
        long id = insertDistributed("NODAYS",
                "{\"spincoMarketCapMillions\":90.0,\"sizeRatio\":0.02,\"distributionDateConfirmed\":true}");

        postSpinoffPrey("run-nodays-1", "NODAYS");

        var promotedAt = jdbc.sql("SELECT promoted_at::text FROM spin_candidate WHERE id = :id")
                .param("id", id).query(String.class).optional();
        assertThat(promotedAt).as("missing daysSinceDistribution must not promote (unchanged)").isEmpty();
    }

    @Test
    void completeEndpointReturns401WithWrongBearer() {
        try {
            rest.post().uri("/api/strigoi-spin/complete")
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
        when(filings.searchSpinoffs(any(), any()))
                .thenReturn(DataSourceResult.unavailable("agora", "agora: 503"));

        JsonNode resp = fetch("r-unavail");
        assertThat(resp.path("output").path("data_source_health").path("status").asText())
                .isEqualTo("unavailable");

        // second identical call — must NOT be served from cache (unhealthy payload not cached)
        fetch("r-unavail-2");

        org.mockito.Mockito.verify(filings, org.mockito.Mockito.times(2))
                .searchSpinoffs(any(), any());
    }
}
