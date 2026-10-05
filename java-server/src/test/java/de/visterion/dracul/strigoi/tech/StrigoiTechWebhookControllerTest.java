package de.visterion.dracul.strigoi.tech;

import de.visterion.dracul.agent.AgentToolCatalog;
import de.visterion.dracul.agent.ToolFetchCache;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionFixtures;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExitProfile;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.research.ResearchMemoryLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The completion hooks and the book payload, driven directly (spec 2026-10-03 §4.3/§4.4). */
class StrigoiTechWebhookControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ExecutorPositionRepository positions = mock(ExecutorPositionRepository.class);
    private final TechBookService book = mock(TechBookService.class);
    private final TechCandidateService candidates = mock(TechCandidateService.class);
    private final TechSettings settings = new TechSettings(10, 3, new BigDecimal("0.03"),
            new BigDecimal("20000"), 90, "depot-1", "depot-1", "USD");

    private StrigoiTechWebhookController controller(boolean executor) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(
                executor ? Map.of("positions", positions) : Map.of());
        return new StrigoiTechWebhookController("tok", mock(PreyRepository.class),
                new ToolFetchCache(new AgentToolCatalog(List.of()), 0),
                mock(HiveMemResearchService.class), mock(ResearchMemoryLinkRepository.class),
                book, candidates, settings, beans.getBeanProvider(ExecutorPositionRepository.class));
    }

    private static TechBookService.Snapshot snapshot(int acceptedThisWeek) {
        return new TechBookService.Snapshot(true, List.of(), List.of(), acceptedThisWeek,
                Set.of(), Set.of(), Set.of());
    }

    private static ExecutorPosition row(long id, String symbol, ExitProfile profile) {
        return ExecutorPositionFixtures.withProfileFields(ExecutorPositionFixtures.withoutKillLevel(
                id, "depot-1", symbol, "BUY", BigDecimal.TEN, new BigDecimal("100"),
                new BigDecimal("65"), new BigDecimal("65"), 1, null, List.of(), "sig-" + id,
                "strigoi-tech", "2026-07-01", null, "OPEN", null, null, null, 0, null, null, null,
                null, null, null, null, null, null, 0, null, null, null, null, null, null, false,
                null, null), profile, null, null, null, false);
    }

    private static Prey prey(String symbol) {
        return new Prey("p-" + symbol, symbol, symbol + " Corp", "TECH_CONVICTION", 0.8, "t",
                List.of(), List.of(), List.of("k"), "12m", "strigoi-tech", "2026-07-08T22:30:00Z", null);
    }

    private static JsonNode body(String output) {
        return JSON.readTree("{\"status\":\"done\",\"output\":" + output + "}");
    }

    /** 4b: catastrophe exits are processed in the accepted-completion hook, independent of prey. */
    @Test
    void catastropheExitFlagsAnOpenConvictionRow() {
        var c = controller(true);
        when(positions.findOpenBySymbolIgnoreCase("depot-1", "SYNA"))
                .thenReturn(row(7L, "SYNA", ExitProfile.CONVICTION));
        when(positions.flagCatastrophe(eq(7L), eq("depot-1"), anyString(), any())).thenReturn(true);

        c.onCompletionAccepted(body("""
                {"prey": [], "catastrophe_exits": [{"symbol": "SYNA", "reason": "synthetic fraud",
                 "evidence": ["synthetic headline / synthetic wire"]}]}
                """), "run-1");
        c.selectForPersist(List.of(), body("{\"prey\": []}"), "run-1");

        verify(positions).flagCatastrophe(eq(7L), eq("depot-1"),
                eq("synthetic fraud [evidence: synthetic headline / synthetic wire]"), any());
        assertThat(c.lastCompletionNotes()).contains("catastrophe_rejected=0");
    }

    /** P2: STANDARD, unknown/closed (not OPEN on the executor connection) -> catastrophe_rejected. */
    @Test
    void catastropheOnAStandardOrUnknownRowIsRejected() {
        var c = controller(true);
        when(positions.findOpenBySymbolIgnoreCase("depot-1", "SYNB"))
                .thenReturn(row(8L, "SYNB", ExitProfile.STANDARD));
        when(positions.findOpenBySymbolIgnoreCase("depot-1", "SYNC")).thenReturn(null);

        c.onCompletionAccepted(body("""
                {"prey": [], "catastrophe_exits": [
                  {"symbol": "SYNB", "reason": "r", "evidence": ["e"]},
                  {"symbol": "SYNC", "reason": "r", "evidence": ["e"]}]}
                """), "run-2");
        c.selectForPersist(List.of(), body("{\"prey\": []}"), "run-2");

        verify(positions, never()).flagCatastrophe(anyLong(), anyString(), anyString(), any());
        assertThat(c.lastCompletionNotes()).contains("catastrophe_rejected=2");
    }

    /** P2: executor disabled -> catastrophe exits dropped with executor_disabled. */
    @Test
    void executorDisabledDropsCatastropheExits() {
        var c = controller(false);

        c.onCompletionAccepted(body("""
                {"prey": [], "catastrophe_exits": [{"symbol": "SYNA", "reason": "r", "evidence": ["e"]}]}
                """), "run-3");
        c.selectForPersist(List.of(), body("{\"prey\": []}"), "run-3");

        assertThat(c.lastCompletionNotes()).contains("executor_disabled=1");
    }

    /** P2: picks are validated first, then capped (capacity 1 here) — picks_over_cap and
     *  ineligible_pick are counted. */
    @Test
    void picksAreValidatedThenCapped() {
        var c = controller(true);
        when(book.snapshot()).thenReturn(snapshot(2));   // week: 3 - 2 = 1
        when(candidates.eligibility(eq("SYNA"), any())).thenReturn(
                new TechEligibility.Verdict(true, List.of(), List.of()));
        when(candidates.eligibility(eq("SYNB"), any())).thenReturn(
                new TechEligibility.Verdict(false, List.of("not_equity:ETF"), List.of()));
        when(candidates.eligibility(eq("SYNC"), any())).thenReturn(
                new TechEligibility.Verdict(true, List.of(), List.of()));

        c.onCompletionAccepted(body("{\"prey\": []}"), "run-4");
        List<Prey> kept = c.selectForPersist(List.of(prey("SYNA"), prey("SYNB"), prey("SYNC")),
                body("{\"prey\": []}"), "run-4");

        assertThat(kept).extracting(Prey::symbol).containsExactly("SYNA");
        assertThat(c.lastCompletionNotes()).contains("picks_over_cap=1").contains("ineligible_pick=1");
    }

    @Test
    void bookPayloadCarriesSlotsAllowanceAndLastNotes() {
        var c = controller(true);
        when(book.snapshot()).thenReturn(new TechBookService.Snapshot(true,
                List.of(row(9L, "SYNA", ExitProfile.CONVICTION)), List.of(), 1, Set.of("SYNE"),
                Set.of("SYNA"), Set.of()));
        when(candidates.lastPrices(any())).thenReturn(Map.of("SYNA", new BigDecimal("120")));
        when(candidates.bookNews(eq("SYNA"), anyInt(), anyInt())).thenReturn(news(true, "synthetic headline"));

        @SuppressWarnings("unchecked")
        Map<String, Object> bookMap = (Map<String, Object>) c.bookPayload(book.snapshot()).get("book");

        assertThat(bookMap.get("slots_free")).isEqualTo(9);
        assertThat(bookMap.get("new_names_allowed_this_week")).isEqualTo(2);
        assertThat(bookMap.get("recently_exited")).isEqualTo(List.of("SYNE"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> open = (List<Map<String, Object>>) bookMap.get("open_positions");
        assertThat(open).singleElement().satisfies(p -> {
            assertThat(p.get("symbol")).isEqualTo("SYNA");
            assertThat((BigDecimal) p.get("pl_pct")).isEqualByComparingTo("20.00");
            assertThat(p.get("half_sold")).isEqualTo(false);
        });
    }

    /** Ruling F4: profile AND quote both down (Agora outage) -> the candidate answer carries
     *  data_source_health unavailable, so a dead source never reads as "all names ineligible". */
    @Test
    void checkCandidateReportsUnavailableWhenProfileAndQuoteBothFailed() {
        var c = controller(true);
        when(book.snapshot()).thenReturn(snapshot(0));
        var verdict = new TechEligibility.Verdict(false,
                List.of("data_unavailable:instrument_type", "data_unavailable:profile"), List.of());
        when(candidates.check(eq("SYNA"), any())).thenReturn(new TechCandidateService.Candidate(
                JSON.createObjectNode().put("symbol", "SYNA"), verdict, true));

        var out = c.checkCandidate("Bearer tok", Map.of("input", Map.of("symbol", " syna ")));

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) out.getBody().get("output");
        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) output.get("data_source_health");
        assertThat(health.get("status")).isEqualTo("unavailable");
        assertThat((String) health.get("detail")).doesNotStartWith("tool-guard: ");
    }

    @Test
    void checkCandidateIsHealthyWhenTheSourceAnswered() {
        var c = controller(true);
        when(book.snapshot()).thenReturn(snapshot(0));
        when(candidates.check(eq("SYNA"), any())).thenReturn(new TechCandidateService.Candidate(
                JSON.createObjectNode().put("symbol", "SYNA"),
                new TechEligibility.Verdict(true, List.of(), List.of()), false));

        var out = c.checkCandidate("Bearer tok", Map.of("input", Map.of("symbol", "SYNA")));

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) out.getBody().get("output");
        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) output.get("data_source_health");
        assertThat(health.get("status")).isEqualTo("healthy");
    }

    private static TechCandidateService.NewsRead news(boolean available, String... headlines) {
        var arr = JSON.createArrayNode();
        for (String h : headlines) arr.addObject().put("headline", h);
        return new TechCandidateService.NewsRead(arr, available);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> health(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("data_source_health");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> openPositions(Map<String, Object> payload) {
        return (List<Map<String, Object>>) ((Map<String, Object>) payload.get("book")).get("open_positions");
    }

    /** Fix round 1: a get_quote outage degrades the book (healthy + partial) — it never flips it
     *  to "unavailable", because the prompt answers that with {"prey": []} and the catastrophe
     *  check reads news, not prices. The news is still delivered. */
    @Test
    void aPriceOutageLeavesTheBookHealthyButPartialAndKeepsTheNews() {
        var c = controller(true);
        var snap = new TechBookService.Snapshot(true, List.of(row(9L, "SYNA", ExitProfile.CONVICTION)),
                List.of(), 0, Set.of(), Set.of("SYNA"), Set.of());
        when(candidates.lastPrices(any())).thenReturn(Map.of());
        when(candidates.bookNews(eq("SYNA"), anyInt(), anyInt()))
                .thenReturn(news(true, "synthetic fraud headline"));

        Map<String, Object> payload = c.bookPayload(snap);

        assertThat(health(payload).get("status")).isEqualTo("healthy");
        assertThat(health(payload).get("partial")).isEqualTo(true);
        assertThat((String) health(payload).get("detail")).contains("no current price for 1 of 1");
        assertThat(openPositions(payload)).singleElement().satisfies(p -> {
            assertThat(p.get("current_close")).isNull();
            assertThat(p.get("news_available")).isEqualTo(true);
            assertThat(p.get("news_since_last_run").toString()).contains("synthetic fraud headline");
        });
    }

    /** Fix round 1: a news outage is visible (partial + detail naming the positions), and the
     *  position says news_available=false so the LLM knows it cannot judge it tonight. */
    @Test
    void aNewsOutageMarksTheBookPartialWithTheAffectedPositions() {
        var c = controller(true);
        var snap = new TechBookService.Snapshot(true, List.of(row(9L, "SYNA", ExitProfile.CONVICTION),
                row(10L, "SYNB", ExitProfile.CONVICTION)), List.of(), 0, Set.of(),
                Set.of("SYNA", "SYNB"), Set.of());
        when(candidates.lastPrices(any())).thenReturn(Map.of("SYNA", new BigDecimal("120"),
                "SYNB", new BigDecimal("90")));
        when(candidates.bookNews(eq("SYNA"), anyInt(), anyInt())).thenReturn(news(false));
        when(candidates.bookNews(eq("SYNB"), anyInt(), anyInt())).thenReturn(news(true));

        Map<String, Object> payload = c.bookPayload(snap);

        assertThat(health(payload).get("status")).isEqualTo("healthy");
        assertThat(health(payload).get("partial")).isEqualTo(true);
        assertThat((String) health(payload).get("detail"))
                .contains("news unavailable for 1 of 2 open position(s)").contains("SYNA")
                .doesNotContain("SYNB").doesNotContain("no current price");
        assertThat(openPositions(payload)).extracting(p -> p.get("news_available"))
                .containsExactly(false, true);
    }

    @Test
    void anEmptyBookIsHealthyWithoutAQuoteCall() {
        var c = controller(true);
        when(candidates.lastPrices(any())).thenReturn(Map.of());

        Map<String, Object> payload = c.bookPayload(snapshot(0));

        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) payload.get("data_source_health");
        assertThat(health.get("status")).isEqualTo("healthy");
    }
}
