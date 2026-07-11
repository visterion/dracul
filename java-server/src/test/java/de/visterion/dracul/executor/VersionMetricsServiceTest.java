package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VersionMetricsServiceTest {

    @Test
    void bothThresholdsMetIsSufficient() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant last = first.plus(14, ChronoUnit.DAYS);
        var repository = mock(VersionMetricsRepository.class);
        when(repository.findGroupedByVersion()).thenReturn(List.of(
                new VersionMetricsRepository.Row("gropar", "1", "v3", 20, first, last, 0.42, 0.6)));

        var service = new VersionMetricsService(repository);
        List<VersionMetricsService.VersionMetrics> result = service.metrics();

        assertThat(result).hasSize(1);
        VersionMetricsService.VersionMetrics metrics = result.get(0);
        assertThat(metrics.agent()).isEqualTo("gropar");
        assertThat(metrics.agentVersion()).isEqualTo("1");
        assertThat(metrics.ruleVersion()).isEqualTo("v3");
        assertThat(metrics.decisions()).isEqualTo(20);
        assertThat(metrics.avgReturn()).isEqualTo(0.42);
        assertThat(metrics.hitRate()).isEqualTo(0.6);
        assertThat(metrics.insufficientSample()).isFalse();
    }

    @Test
    void thirteenDaySpanWithFiftyDecisionsIsInsufficient() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant last = first.plus(13, ChronoUnit.DAYS);
        var repository = mock(VersionMetricsRepository.class);
        when(repository.findGroupedByVersion()).thenReturn(List.of(
                new VersionMetricsRepository.Row("gropar", "1", "v3", 50, first, last, 0.1, 0.5)));

        var service = new VersionMetricsService(repository);
        List<VersionMetricsService.VersionMetrics> result = service.metrics();

        assertThat(result.get(0).insufficientSample()).isTrue();
    }

    @Test
    void thirtyDaySpanWithNineteenDecisionsIsInsufficient() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant last = first.plus(30, ChronoUnit.DAYS);
        var repository = mock(VersionMetricsRepository.class);
        when(repository.findGroupedByVersion()).thenReturn(List.of(
                new VersionMetricsRepository.Row("gropar", "1", "v3", 19, first, last, 0.1, 0.5)));

        var service = new VersionMetricsService(repository);
        List<VersionMetricsService.VersionMetrics> result = service.metrics();

        assertThat(result.get(0).insufficientSample()).isTrue();
    }
}
