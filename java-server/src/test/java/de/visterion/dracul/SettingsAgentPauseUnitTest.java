package de.visterion.dracul;

import de.visterion.dracul.settings.AgentConfigRow;
import de.visterion.dracul.settings.AgentPausePatch;
import de.visterion.dracul.settings.AppSettingsRepository;
import de.visterion.dracul.settings.DataSourceHealthService;
import de.visterion.dracul.settings.SettingsController;
import de.visterion.dracul.vistierie.StrigoiStatus;
import de.visterion.dracul.vistierie.VistierieClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettingsAgentPauseUnitTest {

    private final VistierieClient client = mock(VistierieClient.class);
    private final SettingsController controller =
            new SettingsController(client, mock(AppSettingsRepository.class), null, mock(DataSourceHealthService.class));

    @Test
    void patchPausesAgentAndReturnsUpdatedRow() {
        when(client.listStrigoi()).thenReturn(List.of(
                new StrigoiStatus("strigoi-spin", "hunting", null, null)));
        when(client.getStrigoiDetail("strigoi-spin")).thenReturn(Optional.empty());

        ResponseEntity<AgentConfigRow> resp =
                controller.patchAgentPaused("strigoi-spin", new AgentPausePatch(true));

        verify(client).patchAgent(eq("strigoi-spin"), eq(true));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().paused()).isTrue();
        assertThat(resp.getBody().state()).isEqualTo("paused");
    }

    @Test
    void patchUnknownAgentReturns404() {
        when(client.listStrigoi()).thenReturn(List.of());
        ResponseEntity<AgentConfigRow> resp =
                controller.patchAgentPaused("nope", new AgentPausePatch(true));
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(client, never()).patchAgent(anyString(), anyBoolean());
    }

    @Test
    void patchWithMissingPausedReturns400() {
        ResponseEntity<AgentConfigRow> resp =
                controller.patchAgentPaused("strigoi-spin", new AgentPausePatch(null));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(client, never()).patchAgent(anyString(), anyBoolean());
    }

    @Test
    void resumeKeepsNonPausedTransientState() {
        when(client.listStrigoi()).thenReturn(java.util.List.of(
                new StrigoiStatus("strigoi-merger", "budget-hit", null, null)));
        when(client.getStrigoiDetail("strigoi-merger")).thenReturn(java.util.Optional.empty());

        ResponseEntity<AgentConfigRow> resp =
                controller.patchAgentPaused("strigoi-merger", new AgentPausePatch(false));

        verify(client).patchAgent(eq("strigoi-merger"), eq(false));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().paused()).isFalse();
        assertThat(resp.getBody().state()).isEqualTo("budget-hit");
    }
}
