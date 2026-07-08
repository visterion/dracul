package de.visterion.dracul.executor;

import de.visterion.dracul.vistierie.VistierieClient;
import de.visterion.dracul.vistierie.VistierieRunDetail;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExecutorRunControllerTest {

    @Test
    void runTriggersExecutorAndReturnsDetail() {
        var vistierie = mock(VistierieClient.class);
        var detail = new VistierieRunDetail(
                "run-1", ExecutorDefaults.NAME, "running", "2026-07-08T00:00:00Z", null, null, null);
        when(vistierie.triggerRun(ExecutorDefaults.NAME)).thenReturn(detail);

        var controller = new ExecutorRunController(vistierie);
        var resp = controller.run();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEqualTo(detail);
        verify(vistierie, times(1)).triggerRun(ExecutorDefaults.NAME);
    }
}
