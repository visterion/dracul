package de.visterion.dracul.outcome;

import org.junit.jupiter.api.Test;

import java.util.List;

import static de.visterion.dracul.outcome.CalibrationService.BrierPoint;
import static de.visterion.dracul.outcome.CalibrationService.StopBasisRow;
import static de.visterion.dracul.outcome.CalibrationService.VetoRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class CalibrationServiceTest {

    private final CalibrationService service = new CalibrationService();

    @Test
    void brierMatchesFixedFixture() {
        // predictions [0.8 win, 0.8 loss, 0.6 win] -> ((0.8-1)^2 + (0.8-0)^2 + (0.6-1)^2) / 3 = 0.28
        List<BrierPoint> points = List.of(
                new BrierPoint(0.8, true),
                new BrierPoint(0.8, false),
                new BrierPoint(0.6, true));

        double brier = service.brier(points);

        assertThat(brier).isCloseTo(0.28, offset(1e-9));
    }

    @Test
    void brierResultFlagsInsufficientBelowThirty() {
        List<BrierPoint> points = List.of(
                new BrierPoint(0.8, true),
                new BrierPoint(0.8, false),
                new BrierPoint(0.6, true));

        CalibrationService.BrierResult result = service.brierResult(points);

        assertThat(result.n()).isEqualTo(3);
        assertThat(result.insufficient()).isTrue();
        assertThat(result.brier()).isCloseTo(0.28, offset(1e-9));
    }

    @Test
    void brierResultNotInsufficientAtThirtyOrMore() {
        List<BrierPoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) points.add(new BrierPoint(0.7, i % 2 == 0));

        CalibrationService.BrierResult result = service.brierResult(points);

        assertThat(result.n()).isEqualTo(30);
        assertThat(result.insufficient()).isFalse();
    }

    @Test
    void bucketsGroupByPredictedConfidenceDecileRanges() {
        List<BrierPoint> points = List.of(
                new BrierPoint(0.65, true),
                new BrierPoint(0.62, false),
                new BrierPoint(0.61, true),
                new BrierPoint(0.35, false));

        List<CalibrationService.Bucket> buckets = service.buckets(points);

        assertThat(buckets).hasSize(2);
        CalibrationService.Bucket lowBucket = buckets.stream()
                .filter(b -> b.range().equals("0.0-0.5")).findFirst().orElseThrow();
        assertThat(lowBucket.n()).isEqualTo(1);
        assertThat(lowBucket.observed()).isEqualTo(0.0);

        CalibrationService.Bucket midBucket = buckets.stream()
                .filter(b -> b.range().equals("0.6-0.7")).findFirst().orElseThrow();
        assertThat(midBucket.n()).isEqualTo(3);
        assertThat(midBucket.observed()).isCloseTo(2.0 / 3, offset(1e-4));
    }

    @Test
    void emptyBucketsAreOmitted() {
        List<CalibrationService.Bucket> buckets = service.buckets(List.of(new BrierPoint(0.95, true)));

        assertThat(buckets).hasSize(1);
        assertThat(buckets.get(0).range()).isEqualTo("0.9-1.0");
    }

    @Test
    void hunterBrierResultsGroupByAgent() {
        List<CalibrationService.AgentBrierPoint> points = List.of(
                new CalibrationService.AgentBrierPoint("strigoi-echo", 0.8, true),
                new CalibrationService.AgentBrierPoint("strigoi-echo", 0.8, false),
                new CalibrationService.AgentBrierPoint("strigoi-echo", 0.6, true),
                new CalibrationService.AgentBrierPoint("strigoi-insider", 0.9, true));

        List<CalibrationService.HunterBrier> result = service.hunterBrierResults(points);

        assertThat(result).extracting(CalibrationService.HunterBrier::agent)
                .containsExactly("strigoi-echo", "strigoi-insider");
        CalibrationService.HunterBrier echo = result.get(0);
        assertThat(echo.n()).isEqualTo(3);
        assertThat(echo.brier()).isCloseTo(0.28, offset(1e-4));
        assertThat(echo.insufficient()).isTrue();
    }

    @Test
    void vetoPrecisionMeansExcludeSkippedRowsButCountThemSeparately() {
        List<VetoRow> rows = List.of(
                new VetoRow("PACE_LIMIT", false, 0.5, 1.0, true),
                new VetoRow("PACE_LIMIT", false, 0.3, 1.2, false),
                new VetoRow("PACE_LIMIT", true, null, null, null));

        List<CalibrationService.VetoPrecision> result = service.vetoPrecision(rows);

        assertThat(result).hasSize(1);
        CalibrationService.VetoPrecision precision = result.get(0);
        assertThat(precision.reasonCode()).isEqualTo("PACE_LIMIT");
        assertThat(precision.n()).isEqualTo(3);
        assertThat(precision.skipped()).isEqualTo(1);
        assertThat(precision.meanHypotheticalR20d()).isCloseTo(0.4, offset(1e-9));
        assertThat(precision.meanHypotheticalR60d()).isCloseTo(1.1, offset(1e-9));
        assertThat(precision.stoppedOutPct()).isCloseTo(50.0, offset(1e-9));
    }

    @Test
    void vetoPrecisionGroupsByReasonCode() {
        List<VetoRow> rows = List.of(
                new VetoRow("PACE_LIMIT", false, 0.5, 1.0, true),
                new VetoRow("BUDGET", false, 0.1, 0.2, false));

        List<CalibrationService.VetoPrecision> result = service.vetoPrecision(rows);

        assertThat(result).extracting(CalibrationService.VetoPrecision::reasonCode)
                .containsExactlyInAnyOrder("PACE_LIMIT", "BUDGET");
    }

    @Test
    void normalizeStopBasisMatchesBySubstring() {
        assertThat(service.normalizeStopBasis("entry - 2.5 x ATR22")).isEqualTo("ATR");
        assertThat(service.normalizeStopBasis(
                "swing_low 12.30 (wider than entry - 2.5 x ATR22 11.90)")).isEqualTo("SWING_LOW");
        assertThat(service.normalizeStopBasis("chandelier: long 3xATR")).isEqualTo("ATR");
        assertThat(service.normalizeStopBasis(null)).isEqualTo("OTHER");
        assertThat(service.normalizeStopBasis("manual override")).isEqualTo("OTHER");
    }

    @Test
    void stopBasisStatsGroupsByNormalizedBasis() {
        List<StopBasisRow> rows = List.of(
                new StopBasisRow("entry - 2.5 x ATR22", 0.8, -0.4),
                new StopBasisRow("chandelier: long 3xATR", 1.0, -0.6),
                new StopBasisRow("swing_low 12.30 (wider than entry - 2.5 x ATR22 11.90)", 1.3, -0.3));

        List<CalibrationService.StopBasisStats> result = service.stopBasisStats(rows);

        assertThat(result).hasSize(2);
        CalibrationService.StopBasisStats atr = result.stream()
                .filter(s -> s.basis().equals("ATR")).findFirst().orElseThrow();
        assertThat(atr.n()).isEqualTo(2);
        assertThat(atr.meanRealizedR()).isCloseTo(0.9, offset(1e-9));

        CalibrationService.StopBasisStats swing = result.stream()
                .filter(s -> s.basis().equals("SWING_LOW")).findFirst().orElseThrow();
        assertThat(swing.n()).isEqualTo(1);
        assertThat(swing.meanRealizedR()).isCloseTo(1.3, offset(1e-9));
    }

    @Test
    void latencyComputesMaxAndP95() {
        CalibrationService.LatencyStats stats = service.latency(List.of(1L, 2L, 2L, 3L, 5L));

        assertThat(stats.n()).isEqualTo(5);
        assertThat(stats.maxSeconds()).isEqualTo(5);
        assertThat(stats.p95Seconds()).isGreaterThanOrEqualTo(3L);
    }

    @Test
    void latencyEmptyIsZeroed() {
        CalibrationService.LatencyStats stats = service.latency(List.of());

        assertThat(stats.n()).isEqualTo(0);
        assertThat(stats.maxSeconds()).isEqualTo(0);
        assertThat(stats.p95Seconds()).isEqualTo(0);
    }

    @Test
    void whipsawCountsTrueFlags() {
        List<CalibrationService.WhipsawRowPair> rows = List.of(
                new CalibrationService.WhipsawRowPair(true, true),
                new CalibrationService.WhipsawRowPair(false, true),
                new CalibrationService.WhipsawRowPair(false, false));

        CalibrationService.WhipsawStats stats = service.whipsaw(rows);

        assertThat(stats.reentryWithin10d()).isEqualTo(1);
        assertThat(stats.roundtripUnder5d()).isEqualTo(2);
    }

    @Test
    void slippageMeanAndWorst() {
        CalibrationService.SlippageStats stats = service.slippage(List.of(-0.01, -0.02, -0.15, 0.01));

        assertThat(stats.n()).isEqualTo(4);
        assertThat(stats.mean()).isCloseTo(-0.0425, offset(1e-9));
        assertThat(stats.worst()).isCloseTo(-0.15, offset(1e-9));
    }

    @Test
    void slippageEmptyIsZeroed() {
        CalibrationService.SlippageStats stats = service.slippage(List.of());

        assertThat(stats.n()).isEqualTo(0);
        assertThat(stats.mean()).isEqualTo(0.0);
        assertThat(stats.worst()).isEqualTo(0.0);
    }
}
