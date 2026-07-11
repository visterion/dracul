package de.visterion.dracul.outcome;

import de.visterion.dracul.executor.VersionMetricsService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalibrationControllerTest {

    @Test
    void versionMetricsReturnsServiceResult() {
        var outcomeLogRepository = mock(OutcomeLogRepository.class);
        var calibration = mock(CalibrationService.class);
        var versionMetricsService = mock(VersionMetricsService.class);
        var metrics = new VersionMetricsService.VersionMetrics(
                "gropar", "1", "v3", 25, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-20T00:00:00Z"), 0.3, 0.55, false);
        when(versionMetricsService.metrics()).thenReturn(List.of(metrics));

        var controller = new CalibrationController(outcomeLogRepository, calibration, versionMetricsService);
        var resp = controller.versionMetrics();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEqualTo(Map.of("versions", List.of(metrics)));
        verify(versionMetricsService).metrics();
    }
}
