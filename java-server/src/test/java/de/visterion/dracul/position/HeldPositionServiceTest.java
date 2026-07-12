package de.visterion.dracul.position;

import de.visterion.dracul.depot.AgoraDepotClient;
import de.visterion.dracul.depot.DepotPosition;
import de.visterion.dracul.depot.DepotUnavailableException;
import de.visterion.dracul.depot.PositionsSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeldPositionServiceTest {

    private static final String CONNECTION = "depot-1";

    private AgoraDepotClient depotClient;
    private PositionContextRepository contextRepo;
    private HeldPositionService service;

    @BeforeEach
    void setUp() {
        depotClient = mock(AgoraDepotClient.class);
        contextRepo = mock(PositionContextRepository.class);
        service = new HeldPositionService(depotClient, contextRepo);
    }

    private static DepotPosition depotPosition(String symbol) {
        return new DepotPosition(symbol, BigDecimal.TEN, BigDecimal.valueOf(100),
                BigDecimal.valueOf(1000), BigDecimal.valueOf(50), "USD");
    }

    private static PositionContextRow contextRow(String symbol) {
        return new PositionContextRow("ctx-1", CONNECTION, symbol, "verdict-1", null,
                "3-6m", null, BigDecimal.valueOf(90), BigDecimal.valueOf(95),
                "2026-07-01T00:00:00Z", null, "strigoi");
    }

    @Test
    void includesEveryDepotPositionAndAttachesContextOnlyWhereOpen() {
        when(depotClient.positions(CONNECTION)).thenReturn(
                new PositionsSnapshot(List.of(depotPosition("AAA"), depotPosition("BBB")), "2026-07-13T00:00:00Z"));
        when(contextRepo.findOpenBySymbol(CONNECTION, "AAA")).thenReturn(Optional.of(contextRow("AAA")));
        when(contextRepo.findOpenBySymbol(CONNECTION, "BBB")).thenReturn(Optional.empty());

        List<HeldPosition> out = service.openPositions(CONNECTION);

        assertThat(out).hasSize(2);
        HeldPosition aaa = out.stream().filter(p -> p.symbol().equals("AAA")).findFirst().orElseThrow();
        HeldPosition bbb = out.stream().filter(p -> p.symbol().equals("BBB")).findFirst().orElseThrow();

        assertThat(aaa.verdictId()).isEqualTo("verdict-1");
        assertThat(aaa.horizon()).isEqualTo("3-6m");
        assertThat(aaa.initialStop()).isEqualByComparingTo("90");
        assertThat(aaa.activeStop()).isEqualByComparingTo("95");
        assertThat(aaa.contextSource()).isEqualTo("strigoi");
        assertThat(aaa.quantity()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(aaa.marketValue()).isEqualByComparingTo("1000");

        // BBB is TA-only: depot fields populated, context block entirely null
        assertThat(bbb.verdictId()).isNull();
        assertThat(bbb.killCriteria()).isNull();
        assertThat(bbb.horizon()).isNull();
        assertThat(bbb.thesisSnapshot()).isNull();
        assertThat(bbb.initialStop()).isNull();
        assertThat(bbb.activeStop()).isNull();
        assertThat(bbb.contextSource()).isNull();
        assertThat(bbb.quantity()).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void depotUnavailableReturnsEmptyListInsteadOfThrowing() {
        when(depotClient.positions(CONNECTION)).thenThrow(new DepotUnavailableException("agora down"));

        List<HeldPosition> out = service.openPositions(CONNECTION);

        assertThat(out).isEmpty();
        verify(contextRepo, never()).findOpenBySymbol(anyString(), anyString());
    }

    @Test
    void noDepotPositionsYieldsEmptyListWithoutContextLookups() {
        when(depotClient.positions(CONNECTION)).thenReturn(new PositionsSnapshot(List.of(), "2026-07-13T00:00:00Z"));

        List<HeldPosition> out = service.openPositions(CONNECTION);

        assertThat(out).isEmpty();
        verify(contextRepo, never()).findOpenBySymbol(anyString(), anyString());
    }
}
