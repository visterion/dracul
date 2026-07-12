package de.visterion.dracul.position;

import de.visterion.dracul.depot.AgoraDepotClient;
import de.visterion.dracul.depot.DepotPosition;
import de.visterion.dracul.depot.DepotUnavailableException;
import de.visterion.dracul.depot.PositionsSnapshot;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.verdict.VerdictRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class PositionReconcilerTest {

    private static final String CONNECTION = "depot-1";

    private AgoraDepotClient depotClient;
    private PositionContextRepository contextRepo;
    private VerdictRepository verdictRepo;
    private PreyRepository preyRepo;
    private ObjectMapper mapper;
    private PositionReconciler reconciler;

    @BeforeEach
    void setUp() {
        depotClient = mock(AgoraDepotClient.class);
        contextRepo = mock(PositionContextRepository.class);
        verdictRepo = mock(VerdictRepository.class);
        preyRepo = mock(PreyRepository.class);
        mapper = JsonMapper.builder().build();
        reconciler = new PositionReconciler(depotClient, contextRepo, verdictRepo, preyRepo, mapper, CONNECTION);
    }

    private static DepotPosition depotPosition(String symbol) {
        return new DepotPosition(symbol, BigDecimal.TEN, BigDecimal.valueOf(100),
                BigDecimal.valueOf(1000), BigDecimal.valueOf(50), "USD");
    }

    private static PositionContextRow openRow(String symbol) {
        return new PositionContextRow("ctx-" + symbol, CONNECTION, symbol, "verdict-1", null,
                "3-6m", null, BigDecimal.TEN, BigDecimal.TEN, "2026-07-01T00:00:00Z", null, "reconcile");
    }

    @Test
    void backfillsWithVerdictWhenLatestVerdictExists() {
        when(depotClient.positions(CONNECTION)).thenReturn(
                new PositionsSnapshot(List.of(depotPosition("AAA")), "2026-07-13T00:00:00Z"));
        when(contextRepo.findOpenBySymbol(CONNECTION, "AAA")).thenReturn(Optional.empty());
        when(contextRepo.findAllOpen(CONNECTION)).thenReturn(List.of());

        var latest = new VerdictRepository.LatestVerdictForSymbol(
                "verdict-aaa", "3-6m", "spin-off summary",
                List.of("signal-1"), List.of("risk-1"), List.of("spinoff"));
        when(verdictRepo.findLatestBySymbol("AAA")).thenReturn(Optional.of(latest));
        when(verdictRepo.contributingPreyIdsById("verdict-aaa")).thenReturn(List.of("prey-1"));
        when(preyRepo.findByIds(List.of("prey-1"))).thenReturn(List.of(
                new Prey("prey-1", "AAA", "Acme Corp", "spinoff", 0.8, "thesis text",
                        List.of("signal-1"), List.of("risk-1"),
                        List.of("close below 50dma"), "3-6m", "strigoi-spin", "2026-07-01T00:00:00Z")));

        reconciler.reconcile();

        verify(contextRepo).upsertOnOpen(
                eq(CONNECTION), eq("AAA"), eq("verdict-aaa"),
                argThat(json -> json != null && json.toString().contains("close below 50dma")),
                eq("3-6m"),
                argThat(json -> json != null && json.toString().contains("spin-off summary")),
                isNull(),
                eq("reconcile"));
    }

    @Test
    void backfillsMinimalRowWhenNoVerdictExists() {
        when(depotClient.positions(CONNECTION)).thenReturn(
                new PositionsSnapshot(List.of(depotPosition("BBB")), "2026-07-13T00:00:00Z"));
        when(contextRepo.findOpenBySymbol(CONNECTION, "BBB")).thenReturn(Optional.empty());
        when(contextRepo.findAllOpen(CONNECTION)).thenReturn(List.of());
        when(verdictRepo.findLatestBySymbol("BBB")).thenReturn(Optional.empty());

        reconciler.reconcile();

        verify(contextRepo).upsertOnOpen(CONNECTION, "BBB", null, null, null, null, null, "none");
        verifyNoInteractions(preyRepo);
    }

    @Test
    void closesContextRowForSymbolThatLeftTheDepot() {
        when(depotClient.positions(CONNECTION)).thenReturn(
                new PositionsSnapshot(List.of(), "2026-07-13T00:00:00Z"));
        when(contextRepo.findAllOpen(CONNECTION)).thenReturn(List.of(openRow("CCC")));

        reconciler.reconcile();

        verify(contextRepo).markClosed("ctx-CCC");
        verify(contextRepo, never()).upsertOnOpen(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void depotUnavailableIsANoOpAndDoesNotThrow() {
        when(depotClient.positions(CONNECTION)).thenThrow(new DepotUnavailableException("agora down"));

        reconciler.reconcile();

        verify(contextRepo, never()).upsertOnOpen(any(), any(), any(), any(), any(), any(), any(), any());
        verify(contextRepo, never()).markClosed(anyString());
        verifyNoInteractions(verdictRepo, preyRepo);
    }

    @Test
    void aSingleSymbolsBackfillFailureDoesNotAbortTheRestOfThePass() {
        when(depotClient.positions(CONNECTION)).thenReturn(new PositionsSnapshot(
                List.of(depotPosition("BAD"), depotPosition("GOOD")), "2026-07-13T00:00:00Z"));
        when(contextRepo.findOpenBySymbol(CONNECTION, "BAD")).thenThrow(new RuntimeException("db blip"));
        when(contextRepo.findOpenBySymbol(CONNECTION, "GOOD")).thenReturn(Optional.empty());
        when(contextRepo.findAllOpen(CONNECTION)).thenReturn(List.of());
        when(verdictRepo.findLatestBySymbol("GOOD")).thenReturn(Optional.empty());

        reconciler.reconcile();

        verify(contextRepo).upsertOnOpen(CONNECTION, "GOOD", null, null, null, null, null, "none");
        verify(contextRepo, never()).upsertOnOpen(eq(CONNECTION), eq("BAD"), any(), any(), any(), any(), any(), any());
    }
}
