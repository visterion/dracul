package de.visterion.dracul.depot;

import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DepotControllerTranscriptTest {

    @Test
    void transcriptReturnsNodeWhenPresent() {
        var vistierie = mock(VistierieClient.class);
        JsonNode node = new ObjectMapper().createObjectNode().put("view", "full");
        when(vistierie.getRunTranscript("run-xyz", "full")).thenReturn(node);

        var controller = new DepotController(mock(DepotService.class), mock(DepotChartService.class),
                mock(DepotInstrumentService.class), mock(DepotHistoryService.class), vistierie);

        var res = controller.transcript("run-xyz");

        assertThat(res.expired()).isFalse();
        assertThat(res.transcript()).isEqualTo(node);
    }

    @Test
    void transcriptExpiredWhenClientReturnsNull() {
        var vistierie = mock(VistierieClient.class);
        when(vistierie.getRunTranscript("gone", "full")).thenReturn(null);

        var controller = new DepotController(mock(DepotService.class), mock(DepotChartService.class),
                mock(DepotInstrumentService.class), mock(DepotHistoryService.class), vistierie);

        var res = controller.transcript("gone");

        assertThat(res.expired()).isTrue();
        assertThat(res.transcript()).isNull();
    }
}
