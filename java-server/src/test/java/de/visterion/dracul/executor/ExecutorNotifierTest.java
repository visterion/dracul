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
}
