package de.visterion.dracul.strigoi;

import de.visterion.dracul.error.ErrorResponse;
import de.visterion.dracul.vistierie.StrigoiStatus;
import de.visterion.dracul.vistierie.VistierieClient;
import de.visterion.dracul.vistierie.VistierieRunDetail;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StrigoiRunControllerTest {

    private static final List<StrigoiStatus> KNOWN = List.of(
            new StrigoiStatus("strigoi-spin", "resting", null, null),
            new StrigoiStatus("strigoi-insider", "resting", null, null));

    private static HttpClientErrorException upstream(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(),
                HttpHeaders.EMPTY, new byte[0], null);
    }

    @Test
    void knownStrigoiReturns202WithRunId() {
        var vistierie = mock(VistierieClient.class);
        when(vistierie.listStrigoi()).thenReturn(KNOWN);
        when(vistierie.triggerRun("strigoi-spin")).thenReturn(new VistierieRunDetail(
                "run-42", "strigoi-spin", "running", "2026-07-11T00:00:00Z", null, null, null));

        var resp = new StrigoiRunController(vistierie).trigger("strigoi-spin");

        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        assertThat(resp.getBody()).isEqualTo(new StrigoiRunController.RunTriggered("run-42"));
    }

    @Test
    void unknownStrigoiReturns404WithoutTriggering() {
        var vistierie = mock(VistierieClient.class);
        when(vistierie.listStrigoi()).thenReturn(KNOWN);

        var resp = new StrigoiRunController(vistierie).trigger("strigoi-nope");

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(vistierie, never()).triggerRun(anyString());
    }

    @Test
    void vistierieConflictMapsTo409AgentPaused() {
        var vistierie = mock(VistierieClient.class);
        when(vistierie.listStrigoi()).thenReturn(KNOWN);
        when(vistierie.triggerRun("strigoi-spin")).thenThrow(upstream(HttpStatus.CONFLICT));

        var resp = new StrigoiRunController(vistierie).trigger("strigoi-spin");

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(((ErrorResponse) resp.getBody()).error()).isEqualTo("AGENT_PAUSED");
    }

    @Test
    void vistierieBudgetErrorMapsTo422() {
        var vistierie = mock(VistierieClient.class);
        when(vistierie.listStrigoi()).thenReturn(KNOWN);
        when(vistierie.triggerRun("strigoi-spin")).thenThrow(upstream(HttpStatus.TOO_MANY_REQUESTS));

        var resp = new StrigoiRunController(vistierie).trigger("strigoi-spin");

        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(((ErrorResponse) resp.getBody()).error()).isEqualTo("BUDGET_EXCEEDED");
    }
}
