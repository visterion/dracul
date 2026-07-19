package de.visterion.dracul.depot;

import de.visterion.dracul.auth.CurrentUserHolder;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepotControllerTranscriptTest {

    @AfterEach
    void clearUser() {
        CurrentUserHolder.clear();
    }

    @Test
    void transcriptReturnsNodeWhenPresent() {
        var vistierie = mock(VistierieClient.class);
        var prey = mock(PreyRepository.class);
        JsonNode node = new ObjectMapper().createObjectNode().put("view", "full");
        when(vistierie.getRunTranscript("run-xyz", "full")).thenReturn(node);
        when(prey.runExistsForUser("run-xyz", "default")).thenReturn(true);

        var controller = new DepotController(mock(DepotService.class), mock(DepotChartService.class),
                mock(DepotInstrumentService.class), mock(DepotHistoryService.class), vistierie, prey);

        var res = controller.transcript("run-xyz");

        assertThat(res.expired()).isFalse();
        assertThat(res.transcript()).isEqualTo(node);
    }

    @Test
    void transcriptExpiredWhenClientReturnsNull() {
        var vistierie = mock(VistierieClient.class);
        var prey = mock(PreyRepository.class);
        when(vistierie.getRunTranscript("gone", "full")).thenReturn(null);
        when(prey.runExistsForUser("gone", "default")).thenReturn(true);

        var controller = new DepotController(mock(DepotService.class), mock(DepotChartService.class),
                mock(DepotInstrumentService.class), mock(DepotHistoryService.class), vistierie, prey);

        var res = controller.transcript("gone");

        assertThat(res.expired()).isTrue();
        assertThat(res.transcript()).isNull();
    }

    @Test
    void transcriptUnknownOrForeignRunIs404AndNeverCallsVistierie() {
        var vistierie = mock(VistierieClient.class);
        var prey = mock(PreyRepository.class);
        when(prey.runExistsForUser("foreign", "default")).thenReturn(false);

        var controller = new DepotController(mock(DepotService.class), mock(DepotChartService.class),
                mock(DepotInstrumentService.class), mock(DepotHistoryService.class), vistierie, prey);

        assertThatThrownBy(() -> controller.transcript("foreign"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);

        verify(vistierie, never()).getRunTranscript(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
