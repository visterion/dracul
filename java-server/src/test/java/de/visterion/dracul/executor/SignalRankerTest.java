package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SignalRankerTest {

    private final SignalRanker ranker = new SignalRanker();

    private ExecutorSignal signal(String id, String mechanism, double confidence, String createdAt) {
        return new ExecutorSignal(id, "hunter", "v1", "ACME", "LONG",
                confidence, mechanism, List.of(), "3m", new BigDecimal("100"),
                "PENDING", createdAt);
    }

    private ExecutorPosition position(String symbol, String sourceSignalId) {
        return new ExecutorPosition(1L, "depot-1", symbol, "BUY", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), 1, null,
                List.of(), sourceSignalId, "hunter", "2026-06-01", null, "OPEN", "brk-1",
                new BigDecimal("100"), null, 0, null, null, null, null, null, null, null, null, null, 0, null, null,
                null, null, null, null, false, null, null);
    }

    @Test
    void diversifiesByMechanismFirst() {
        ExecutorSignal heldHigh = signal("held-high", "PEAD", 0.95, "2026-07-09T00:00:00Z");   // held + freshest
        ExecutorSignal newLow = signal("new-low", "MERGER_ARB", 0.30, "2026-07-03T00:00:00Z");  // novel, fresher than new-high
        ExecutorSignal newHigh = signal("new-high", "SPINOFF", 0.90, "2026-07-01T00:00:00Z");  // novel, older

        List<ExecutorPosition> open = List.of(position("HELD", "src-1"));
        Map<String, String> openMechanisms = Map.of("HELD", "PEAD");

        List<ExecutorSignal> ranked = ranker.rank(List.of(heldHigh, newLow, newHigh), open, openMechanisms);
        assertThat(ranked).extracting(ExecutorSignal::signalId)
                .containsExactly("new-low", "new-high", "held-high");
    }

    @Test
    void sameNovelty_freshestFirst() {   // renamed from tieOnMechanismNoveltyAndConfidence_freshestFirst
        ExecutorSignal older = signal("older", "SPINOFF", 0.80, "2026-07-01T00:00:00Z");
        ExecutorSignal fresher = signal("fresher", "MERGER_ARB", 0.80, "2026-07-05T00:00:00Z");
        assertThat(ranker.rank(List.of(older, fresher), List.of(), Map.of()))
                .extracting(ExecutorSignal::signalId).containsExactly("fresher", "older");
    }

    @Test
    void emptyBook_freshnessOrdering() {   // renamed from emptyBook_pureConfidenceOrdering
        ExecutorSignal high = signal("high", "SPINOFF", 0.90, "2026-07-01T00:00:00Z");
        ExecutorSignal mid = signal("mid", "MERGER_ARB", 0.60, "2026-07-02T00:00:00Z");
        ExecutorSignal low = signal("low", "PEAD", 0.40, "2026-07-03T00:00:00Z");
        assertThat(ranker.rank(List.of(low, high, mid), List.of(), Map.of()))
                .extracting(ExecutorSignal::signalId).containsExactly("low", "mid", "high");
    }

    @Test
    void freshnessBeatsConfidence() {
        ExecutorSignal olderHigh = signal("older-high", "PEAD", 0.90, "2026-07-01T00:00:00Z");
        ExecutorSignal newerLow = signal("newer-low", "PEAD", 0.50, "2026-07-02T00:00:00Z");
        assertThat(ranker.rank(List.of(olderHigh, newerLow), List.of(), Map.of()))
                .extracting(ExecutorSignal::signalId).containsExactly("newer-low", "older-high");
    }

    @Test
    void equalCreatedAtFallsBackToConfidence() {
        ExecutorSignal a = signal("a", "PEAD", 0.50, "2026-07-01T00:00:00Z");
        ExecutorSignal b = signal("b", "PEAD", 0.90, "2026-07-01T00:00:00Z");   // byte-identical createdAt
        assertThat(ranker.rank(List.of(a, b), List.of(), Map.of()))
                .extracting(ExecutorSignal::signalId).containsExactly("b", "a");
    }

    @Test
    void nullCreatedAtAndNullConfidenceSortLast() {
        ExecutorSignal dated = signal("dated", "PEAD", 0.50, "2026-07-01T00:00:00Z");
        ExecutorSignal undated = signal("undated", "PEAD", 0.90, null);
        assertThat(ranker.rank(List.of(undated, dated), List.of(), Map.of()))
                .extracting(ExecutorSignal::signalId).containsExactly("dated", "undated");
        ExecutorSignal noConf = new ExecutorSignal("noconf", "hunter", "v1", "ACME", "LONG", null, "PEAD",
                List.of(), "3m", new BigDecimal("100"), "PENDING", "2026-07-01T00:00:00Z");
        assertThat(ranker.rank(List.of(noConf, dated), List.of(), Map.of()))
                .extracting(ExecutorSignal::signalId).containsExactly("dated", "noconf");
    }

    @Test
    void openMechanisms_buildsSymbolToMechanismMap() {
        ExecutorSignalRepository signalRepo = mock(ExecutorSignalRepository.class);
        when(signalRepo.findById("src-1")).thenReturn(
                signal("src-1", "PEAD", 0.9, "2026-06-01T00:00:00Z"));
        when(signalRepo.findById("src-missing")).thenReturn(null);

        List<ExecutorPosition> open = List.of(
                position("HELD", "src-1"),
                position("NOSOURCE", null),
                position("MISSING", "src-missing"));

        Map<String, String> result = SignalRanker.openMechanisms(open, signalRepo);

        assertThat(result).containsExactly(Map.entry("HELD", "PEAD"));
    }
}
