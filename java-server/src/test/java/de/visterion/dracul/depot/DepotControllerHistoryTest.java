package de.visterion.dracul.depot;

import de.visterion.dracul.auth.CurrentUserHolder;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DepotControllerHistoryTest {

    @AfterEach
    void clearUser() { CurrentUserHolder.clear(); }

    @Test
    void historyPassesCurrentUserAndReturnsEntries() {
        CurrentUserHolder.set("alice@x.com");
        var history = mock(DepotHistoryService.class);
        when(history.history("depot-1", "alice@x.com")).thenReturn(List.of(
                new DepotHistoryEntry("ORDER", "AAPL", "buy", null, null, null, null, "filled", "o-1",
                        null, null, null, true, null)));

        var controller = new DepotController(mock(DepotService.class), mock(DepotChartService.class),
                mock(DepotInstrumentService.class), history, mock(VistierieClient.class), mock(PreyRepository.class));
        var out = controller.history("depot-1");

        assertThat(out.entries()).hasSize(1);
        assertThat(out.entries().get(0).symbol()).isEqualTo("AAPL");
        assertThat(out.error()).isNull();
        verify(history).history("depot-1", "alice@x.com");
    }
}
