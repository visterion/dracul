package de.visterion.dracul.voievod;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.pattern.PatternRepository;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoievodOutcomeControllerTest {

    private static final String BEARER = "Bearer tok";

    private PreyRepository preyRepo;
    private AgoraMarketData marketData;
    private PatternRepository patternRepo;
    private VoievodOutcomeController controller;

    @BeforeEach
    void setUp() {
        preyRepo = mock(PreyRepository.class);
        marketData = mock(AgoraMarketData.class);
        patternRepo = mock(PatternRepository.class);
        controller = new VoievodOutcomeController("tok", preyRepo, marketData, patternRepo);
    }

    private Prey prey(String id, String symbol, String discoveredAt, String horizon) {
        return new Prey(id, symbol, symbol + " Corp", "SPINOFF",
                0.8, "thesis text", List.of("signal"), List.of("risk"),
                List.of("Close below 42"), horizon, "strigoi-spin", discoveredAt);
    }

    private List<OhlcBar> bars(double... closes) {
        var out = new ArrayList<OhlcBar>();
        LocalDate d = LocalDate.of(2025, 1, 1);
        for (double c : closes) {
            var close = BigDecimal.valueOf(c);
            out.add(new OhlcBar(d, close, close, close, close, 1_000));
            d = d.plusDays(1);
        }
        return out;
    }

    // =========================================================================
    // 401 on bad/missing bearer token
    // =========================================================================

    @Test
    void fetchElapsedPrey_badToken_returns401() {
        var resp = controller.fetchElapsedPrey("Bearer wrong", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(preyRepo, marketData);
    }

    @Test
    void fetchElapsedPrey_missingToken_returns401() {
        var resp = controller.fetchElapsedPrey(null, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // =========================================================================
    // Response shape: symbol/anomalyType/thesis/killCriteria/discoveredAt/horizon
    // + condensed OHLC first/last/min/max closes
    // =========================================================================

    @Test
    void fetchElapsedPrey_returnsCondensedOhlcAndMarksReviewed() {
        // Horizon "3m" discovered far enough in the past that it elapsed >30d ago.
        var p = prey("id-1", "ACME", "2020-01-01T00:00:00Z", "3m");
        when(preyRepo.findElapsedUnreviewed(eq("default"), isNull())).thenReturn(List.of(p));
        when(marketData.dailyOhlcHistory(eq("ACME"), anyInt()))
                .thenReturn(bars(100, 90, 120, 80));

        var resp = controller.fetchElapsedPrey(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) resp.getBody().get("output");
        @SuppressWarnings("unchecked")
        var preyList = (List<Map<String, Object>>) output.get("prey");

        assertThat(preyList).hasSize(1);
        var entry = preyList.get(0);
        assertThat(entry.get("symbol")).isEqualTo("ACME");
        assertThat(entry.get("anomalyType")).isEqualTo("SPINOFF");
        assertThat(entry.get("thesis")).isEqualTo("thesis text");
        assertThat(entry.get("killCriteria")).isEqualTo(List.of("Close below 42"));
        assertThat(entry.get("discoveredAt")).isEqualTo("2020-01-01T00:00:00Z");
        assertThat(entry.get("horizon")).isEqualTo("3m");

        @SuppressWarnings("unchecked")
        var ohlc = (Map<String, Object>) entry.get("ohlc");
        assertThat((BigDecimal) ohlc.get("firstClose")).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat((BigDecimal) ohlc.get("lastClose")).isEqualByComparingTo(BigDecimal.valueOf(80));
        assertThat((BigDecimal) ohlc.get("minClose")).isEqualByComparingTo(BigDecimal.valueOf(80));
        assertThat((BigDecimal) ohlc.get("maxClose")).isEqualByComparingTo(BigDecimal.valueOf(120));

        assertThat(output.get("capped")).isEqualTo(false);
        assertThat(output.get("cap")).isEqualTo(25);

        verify(preyRepo).markOutcomeReviewed(List.of("id-1"));
    }

    // =========================================================================
    // Prey whose horizon hasn't elapsed >30d ago is excluded, not reviewed
    // =========================================================================

    @Test
    void fetchElapsedPrey_excludesPreyWhoseHorizonHasNotElapsed() {
        var stillOpen = prey("id-open", "FOO", LocalDate.now().minusDays(5) + "T00:00:00Z", "1y");
        when(preyRepo.findElapsedUnreviewed(eq("default"), isNull())).thenReturn(List.of(stillOpen));

        var resp = controller.fetchElapsedPrey(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) resp.getBody().get("output");
        @SuppressWarnings("unchecked")
        var preyList = (List<Map<String, Object>>) output.get("prey");
        assertThat(preyList).isEmpty();
        verify(preyRepo).markOutcomeReviewed(List.of());
        verifyNoInteractions(marketData);
    }

    // =========================================================================
    // Cap at 25 prey per run (oldest first — repo already returns oldest first),
    // and the cap is noted in the response.
    // =========================================================================

    @Test
    void fetchElapsedPrey_capsAt25AndNotesCapInResponse() {
        var many = new ArrayList<Prey>();
        for (int i = 0; i < 30; i++) {
            many.add(prey("id-" + i, "SYM" + i, "2020-01-01T00:00:00Z", "3m"));
        }
        when(preyRepo.findElapsedUnreviewed(eq("default"), isNull())).thenReturn(many);
        when(marketData.dailyOhlcHistory(anyString(), anyInt())).thenReturn(bars(100, 110));

        var resp = controller.fetchElapsedPrey(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) resp.getBody().get("output");
        @SuppressWarnings("unchecked")
        var preyList = (List<Map<String, Object>>) output.get("prey");

        assertThat(preyList).hasSize(25);
        assertThat(output.get("capped")).isEqualTo(true);
        assertThat(output.get("cap")).isEqualTo(25);
        // oldest first: the 25 chosen must be id-0..id-24
        assertThat(preyList).extracting(e -> e.get("symbol"))
                .containsExactly(
                        "SYM0", "SYM1", "SYM2", "SYM3", "SYM4", "SYM5", "SYM6", "SYM7", "SYM8", "SYM9",
                        "SYM10", "SYM11", "SYM12", "SYM13", "SYM14", "SYM15", "SYM16", "SYM17", "SYM18",
                        "SYM19", "SYM20", "SYM21", "SYM22", "SYM23", "SYM24");
    }

    // =========================================================================
    // OHLC fetch failure degrades to an empty ohlc block, does not break the feed
    // =========================================================================

    @Test
    void fetchElapsedPrey_ohlcFailure_degradesGracefully() {
        var p = prey("id-fail", "BAD", "2020-01-01T00:00:00Z", "3m");
        when(preyRepo.findElapsedUnreviewed(eq("default"), isNull())).thenReturn(List.of(p));
        when(marketData.dailyOhlcHistory(eq("BAD"), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "agora down"));

        var resp = controller.fetchElapsedPrey(BEARER, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) resp.getBody().get("output");
        @SuppressWarnings("unchecked")
        var preyList = (List<Map<String, Object>>) output.get("prey");
        assertThat(preyList).hasSize(1);
        @SuppressWarnings("unchecked")
        var ohlc = (Map<String, Object>) preyList.get(0).get("ohlc");
        assertThat(ohlc).isEmpty();
        verify(preyRepo).markOutcomeReviewed(List.of("id-fail"));
    }

    // =========================================================================
    // lookback_days input is forwarded to the repository
    // =========================================================================

    @Test
    void fetchElapsedPrey_forwardsLookbackDaysToRepository() {
        when(preyRepo.findElapsedUnreviewed(eq("default"), eq(90))).thenReturn(List.of());

        Map<String, Object> body = Map.of("input", Map.of("lookback_days", 90));
        var resp = controller.fetchElapsedPrey(BEARER, body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(preyRepo).findElapsedUnreviewed(eq("default"), eq(90));
    }

    // =========================================================================
    // /complete: bad/missing bearer token → 401, nothing touched
    // =========================================================================

    @Test
    void complete_badToken_returns401() throws Exception {
        JsonNode body = JsonMapper.builder().build().readTree("{\"status\":\"done\",\"output\":{\"patterns\":[]}}");

        var resp = controller.complete("Bearer wrong", "run-1", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(patternRepo);
    }

    // =========================================================================
    // /complete: non-done status → acknowledged (204) without persisting
    // =========================================================================

    @Test
    void complete_nonDoneStatus_acknowledgesWithoutPersisting() throws Exception {
        String json = """
                {
                  "status": "failed",
                  "output": { "patterns": [] }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);

        var resp = controller.complete(BEARER, "run-2", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verifyNoInteractions(patternRepo);
    }

    // =========================================================================
    // /complete: done status inserts a PENDING pattern per proposal, with
    // evidence_count = evidence_symbols.length
    // =========================================================================

    @Test
    void complete_doneStatus_insertsPendingPatternWithEvidenceCount() throws Exception {
        String json = """
                {
                  "status": "done",
                  "output": {
                    "patterns": [
                      { "applies_to_strigoi": "strigoi-spin",
                        "statement": "Tech spin-offs outperform industrial spin-offs",
                        "evidence_symbols": ["GEHC", "KVUE", "SOLV"] }
                    ]
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);
        when(patternRepo.existsPendingStatement(eq("default"), anyString())).thenReturn(false);

        var resp = controller.complete(BEARER, "run-3", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        ArgumentCaptor<Integer> evidenceCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(patternRepo).insertProposal(eq("default"), eq("strigoi-spin"),
                eq("Tech spin-offs outperform industrial spin-offs"), evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue()).isEqualTo(3);
    }

    // =========================================================================
    // /complete: duplicate statement (existsPendingStatement true) is skipped
    // =========================================================================

    @Test
    void complete_duplicateStatement_skipsInsert() throws Exception {
        String json = """
                {
                  "status": "done",
                  "output": {
                    "patterns": [
                      { "applies_to_strigoi": "strigoi-spin",
                        "statement": "Tech spin-offs outperform industrial spin-offs",
                        "evidence_symbols": ["GEHC"] }
                    ]
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);
        when(patternRepo.existsPendingStatement(eq("default"),
                eq("Tech spin-offs outperform industrial spin-offs"))).thenReturn(true);

        var resp = controller.complete(BEARER, "run-4", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(patternRepo, never()).insertProposal(any(), any(), any(), anyInt());
    }

    // =========================================================================
    // /complete: succeeded status also persists (same as done)
    // =========================================================================

    @Test
    void complete_succeededStatus_insertsPendingPattern() throws Exception {
        String json = """
                {
                  "status": "succeeded",
                  "output": {
                    "patterns": [
                      { "applies_to_strigoi": "strigoi-insider",
                        "statement": "CFO presence in insider clusters lifts follow-through",
                        "evidence_symbols": ["TRNS", "MDLY"] }
                    ]
                  }
                }
                """;
        JsonNode body = JsonMapper.builder().build().readTree(json);
        when(patternRepo.existsPendingStatement(eq("default"), anyString())).thenReturn(false);

        var resp = controller.complete(BEARER, "run-5", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(patternRepo).insertProposal(eq("default"), eq("strigoi-insider"),
                eq("CFO presence in insider clusters lifts follow-through"), eq(2));
    }
}
