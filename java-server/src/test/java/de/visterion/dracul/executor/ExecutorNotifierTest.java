package de.visterion.dracul.executor;

import de.visterion.dracul.notify.TelegramNotifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExecutorNotifierTest {

    private final TelegramNotifier telegram = mock(TelegramNotifier.class);
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final ExecutorSignalRepository signalRepo = mock(ExecutorSignalRepository.class);
    private final ObjectMapper json = new ObjectMapper();

    private ExecutorNotifier notifier(boolean enabled) {
        return new ExecutorNotifier(telegram, positionRepo, signalRepo, enabled, "USD");
    }

    private ExecutorPosition pos(long id, String sym, BigDecimal qty, BigDecimal entry, String expires) {
        return new ExecutorPosition(id, "depot-1", sym, "BUY", qty, entry,
                new BigDecimal("178.00"), new BigDecimal("178.00"), 1, null, List.of(),
                "sig-1", "strigoi-pead", "2026-07-20", null, "OPEN", "bo-1", null, null, 0,
                null, null, null, null, "so-1", "Tech", null, null, null, 0, null,
                expires, null, null, null, null);
    }

    private ExecutorSignal sig(String mech, Double conf, JsonNode thesis) {
        return new ExecutorSignal("sig-1", "strigoi-pead", "v1", "AAPL", "BUY", conf,
                mech, List.of(), "3-6w", new BigDecimal("187.00"), "PENDING",
                "2026-07-20", thesis);
    }

    @Test
    void disabled_sendsNothing() {
        notifier(false).notifyStopRatchet(pos(1, "AAPL", new BigDecimal("12"), new BigDecimal("187.50"), null),
                new BigDecimal("178.00"), new BigDecimal("184.50"), "depot-1");
        verifyNoInteractions(telegram);
    }

    @Test
    void investedTotal_sumsOnlyFilledOpenPositions() {
        when(positionRepo.findOpen()).thenReturn(List.of(
                pos(1, "AAPL", new BigDecimal("12"), new BigDecimal("187.50"), null),
                pos(2, "MSFT", new BigDecimal("10"), new BigDecimal("400.00"), "2026-07-25")
        ));
        notifier(true).notifyStopRatchet(pos(1, "AAPL", new BigDecimal("12"), new BigDecimal("187.50"), null),
                new BigDecimal("178.00"), new BigDecimal("184.50"), "depot-1");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram).notifyDigest(text.capture());
        assertThat(text.getValue()).contains("Investiert gesamt: 2.250,00 USD");
    }

    @Test
    void investedTotal_repoThrows_lineOmittedNoThrow() {
        when(positionRepo.findOpen()).thenThrow(new RuntimeException("db down"));
        notifier(true).notifyStopRatchet(pos(1, "AAPL", new BigDecimal("12"), new BigDecimal("187.50"), null),
                new BigDecimal("178.00"), new BigDecimal("184.50"), "depot-1");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram).notifyDigest(text.capture());
        assertThat(text.getValue()).doesNotContain("Investiert gesamt");
    }

    @Test
    void entryPlaced_rendersAllLines() {
        when(positionRepo.findOpen()).thenReturn(List.of());
        ExecutorSignal s = sig("PEAD", 0.72, json.getNodeFactory().textNode("Post-earnings drift"));
        notifier(true).notifyEntryPlaced(s, "BUY", new BigDecimal("12"), new BigDecimal("187.50"),
                new BigDecimal("178.00"), "depot-1");
        ArgumentCaptor<String> t = ArgumentCaptor.forClass(String.class);
        verify(telegram).notifyDigest(t.capture());
        assertThat(t.getValue())
                .contains("ENTRY PLATZIERT AAPL (depot-1)")
                .contains("BUY 12 @ 187,50 — Stop 178,00")
                .contains("Mechanismus: PEAD · Confidence: 0,72")
                .contains("These: Post-earnings drift")
                .contains("Betrag: 2.250,00 USD");
    }

    @Test
    void entryFilled_resolvesThesisViaSourceSignal() {
        when(positionRepo.findOpen()).thenReturn(List.of());
        when(signalRepo.findById("sig-1")).thenReturn(sig("PEAD", 0.72, json.getNodeFactory().textNode("Drift")));
        notifier(true).notifyEntryFilled(pos(1, "AAPL", new BigDecimal("12"), new BigDecimal("187.50"), null),
                new BigDecimal("12"), new BigDecimal("187.55"), "depot-1");
        ArgumentCaptor<String> t = ArgumentCaptor.forClass(String.class);
        verify(telegram).notifyDigest(t.capture());
        assertThat(t.getValue())
                .contains("ENTRY GEFÜLLT AAPL (depot-1)")
                .contains("BUY 12 @ 187,55")
                .contains("These: Drift")
                .contains("Betrag: 2.250,60 USD");
    }

    @Test
    void entryFilled_signalRepoThrows_stillSendsCoreLineNoThrow() {
        when(positionRepo.findOpen()).thenReturn(List.of());
        when(signalRepo.findById("sig-1")).thenThrow(new RuntimeException("db down"));
        notifier(true).notifyEntryFilled(pos(1, "AAPL", new BigDecimal("12"), new BigDecimal("187.50"), null),
                new BigDecimal("12"), new BigDecimal("187.55"), "depot-1");
        ArgumentCaptor<String> t = ArgumentCaptor.forClass(String.class);
        verify(telegram).notifyDigest(t.capture());
        assertThat(t.getValue()).contains("ENTRY GEFÜLLT AAPL");
        assertThat(t.getValue()).doesNotContain("These:");
    }

    @Test
    void exit_rendersReasonAndRealizedR() {
        when(positionRepo.findOpen()).thenReturn(List.of());
        when(signalRepo.findById("sig-1")).thenReturn(sig("PEAD", 0.72, null));
        notifier(true).notifyExit(pos(1, "AAPL", new BigDecimal("12"), new BigDecimal("187.50"), null),
                "TAKE_PROFIT", new BigDecimal("201.30"), new BigDecimal("1.8"), "depot-1");
        ArgumentCaptor<String> t = ArgumentCaptor.forClass(String.class);
        verify(telegram).notifyDigest(t.capture());
        assertThat(t.getValue())
                .contains("EXIT AAPL (depot-1) — TAKE_PROFIT")
                .contains("12 @ 201,30 · realized 1,8R")
                .contains("Betrag: 2.415,60 USD");
    }

    @Test
    void tranche2_rendersAddAndTotals() {
        when(positionRepo.findOpen()).thenReturn(List.of());
        when(signalRepo.findById("sig-1")).thenReturn(sig("PEAD", 0.72, null));
        notifier(true).notifyTranche2(pos(1, "AAPL", new BigDecimal("12"), new BigDecimal("187.50"), null),
                new BigDecimal("6"), new BigDecimal("205.00"), new BigDecimal("18"),
                new BigDecimal("193.50"), "NEW_HIGH", "depot-1");
        ArgumentCaptor<String> t = ArgumentCaptor.forClass(String.class);
        verify(telegram).notifyDigest(t.capture());
        assertThat(t.getValue())
                .contains("TRANCHE 2 AAPL (depot-1) — NEW_HIGH")
                .contains("+6 @ 205,00 → gesamt 18 @ Ø 193,50")
                .contains("Betrag: 1.230,00 USD");
    }
}
