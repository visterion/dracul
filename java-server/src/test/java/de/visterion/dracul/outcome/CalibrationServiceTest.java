package de.visterion.dracul.outcome;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
                new CalibrationService.AgentBrierPoint("strigoi-echo", 0.8, true, false),
                new CalibrationService.AgentBrierPoint("strigoi-echo", 0.8, false, false),
                new CalibrationService.AgentBrierPoint("strigoi-echo", 0.6, true, false),
                new CalibrationService.AgentBrierPoint("strigoi-insider", 0.9, true, false));

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
                new VetoRow("PACE_LIMIT", false, 0.5, 1.0, true, "emission"),
                new VetoRow("PACE_LIMIT", false, 0.3, 1.2, false, "emission"),
                new VetoRow("PACE_LIMIT", true, null, null, null, null));

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
                new VetoRow("PACE_LIMIT", false, 0.5, 1.0, true, "emission"),
                new VetoRow("BUDGET", false, 0.1, 0.2, false, "emission"));

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

    @Test
    void threeLlmSkipRowsCountAsThree() {
        // vetoPrecision itself is unchanged by SP3: it still groups by reason_code and counts
        // rows. What changed is that findVetoRows now hands it one row per SIGNAL.
        var rows = List.of(
                new VetoRow("LLM_SKIP", false, 0.5, 1.0, false, "emission"),
                new VetoRow("LLM_SKIP", false, 0.7, 1.2, false, "emission"),
                new VetoRow("LLM_SKIP", false, 0.9, 1.4, true, "emission"));

        var result = service.vetoPrecision(rows);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().reasonCode()).isEqualTo("LLM_SKIP");
        assertThat(result.getFirst().n()).isEqualTo(3);
    }

    /** The caveat list is a documented API contract: documentation/api.md and
     *  documentation/chronicle.md both state the exact count, and Chronicle renders them as a
     *  footnote list. Task 5 (SP12) adds the reconstructed-anchors caveat. */
    @Test
    void behaviorCaveatsHasFiveEntriesIncludingTheReconstructedNote() {
        assertThat(CalibrationService.BEHAVIOR_CAVEATS).hasSize(5);
        assertThat(CalibrationService.BEHAVIOR_CAVEATS).last().isEqualTo(
                "counterfactuals of pre-V49 signals walk anchors reconstructed from today's daily "
                        + "history (see reconstructed counts)");
    }

    /** Task 5: {@code vetoPrecision} counts, per reason_code, how many of the non-skipped
     *  ("counted") rows were walked from reconstructed anchors ({@code reconstructed}), and how
     *  many of THOSE contributed to {@code mean_hypothetical_r_20d} specifically
     *  ({@code reconstructed_r20} — a row can be reconstructed but have a null r_after_20d, e.g.
     *  the horizon has not filled yet). Skipped rows never count towards either, matching the
     *  existing skipped/counted split. */
    @Test
    void vetoPrecisionCountsReconstructedPerPopulation() {
        List<VetoRow> rows = List.of(
                new VetoRow("LLM_SKIP", false, 1.0, 1.0, false, "reconstructed"),
                new VetoRow("LLM_SKIP", false, null, null, false, "reconstructed"),
                new VetoRow("LLM_SKIP", false, -1.0, -1.0, false, "emission"),
                new VetoRow("LLM_SKIP", true, null, null, null, "reconstructed"));

        List<CalibrationService.VetoPrecision> result = service.vetoPrecision(rows);

        assertThat(result).hasSize(1);
        CalibrationService.VetoPrecision precision = result.get(0);
        assertThat(precision.n()).isEqualTo(4);
        assertThat(precision.skipped()).isEqualTo(1);
        assertThat(precision.reconstructed()).isEqualTo(2);
        assertThat(precision.reconstructedR20()).isEqualTo(1);
    }

    /** Task 5: {@code hunterBrierResults} counts, per agent, how many of the points contributing
     *  to that agent's Brier score were walked from reconstructed anchors — without changing the
     *  Brier score or n themselves (reconstructed points still contribute to the math exactly like
     *  any other point; the flag is visibility only). */
    @Test
    void hunterBrierCountsReconstructed() {
        List<CalibrationService.AgentBrierPoint> reconstructedPoints = List.of(
                new CalibrationService.AgentBrierPoint("strigoi-merger", 0.8, true, true),
                new CalibrationService.AgentBrierPoint("strigoi-merger", 0.6, false, true),
                new CalibrationService.AgentBrierPoint("strigoi-merger", 0.9, true, false));
        List<CalibrationService.AgentBrierPoint> plainPoints = List.of(
                new CalibrationService.AgentBrierPoint("strigoi-merger", 0.8, true, false),
                new CalibrationService.AgentBrierPoint("strigoi-merger", 0.6, false, false),
                new CalibrationService.AgentBrierPoint("strigoi-merger", 0.9, true, false));

        List<CalibrationService.HunterBrier> reconstructedResult =
                service.hunterBrierResults(reconstructedPoints);
        List<CalibrationService.HunterBrier> plainResult = service.hunterBrierResults(plainPoints);

        CalibrationService.HunterBrier reconstructed = reconstructedResult.get(0);
        CalibrationService.HunterBrier plain = plainResult.get(0);
        assertThat(reconstructed.reconstructed()).isEqualTo(2);
        assertThat(reconstructed.brier()).isEqualTo(plain.brier());
        assertThat(reconstructed.n()).isEqualTo(plain.n());
    }

    /** Task 5: both {@code reconstructed} and {@code reconstructed_r20} on {@code VetoPrecision},
     *  and {@code reconstructed} on {@code HunterBrier}, must serialize under snake_case keys with
     *  the app's Jackson 3 {@code ObjectMapper} — the same mapper OutcomeBatchJob and
     *  OutcomeLogRepository already use. */
    @Test
    void jsonKeys() {
        ObjectMapper mapper = new ObjectMapper();

        CalibrationService.VetoPrecision precision = new CalibrationService.VetoPrecision(
                "LLM_SKIP", 4, 1, 0.5, 1.0, 50.0, 2, 1);
        String precisionJson = mapper.writeValueAsString(precision);
        assertThat(precisionJson).contains("\"reconstructed\"");
        assertThat(precisionJson).contains("\"reconstructed_r20\"");

        CalibrationService.HunterBrier hunterBrier = new CalibrationService.HunterBrier(
                "strigoi-merger", 0.28, 3, true, List.of(), 2);
        String hunterBrierJson = mapper.writeValueAsString(hunterBrier);
        assertThat(hunterBrierJson).contains("\"reconstructed\"");
    }

    /** Regression: with no reconstructed rows in the fixture, the existing numbers are unchanged
     *  and the new counts are simply 0. */
    @Test
    void vetoPrecisionUnchangedWithoutReconstructedRows() {
        List<VetoRow> rows = List.of(
                new VetoRow("PACE_LIMIT", false, 0.5, 1.0, true, "emission"),
                new VetoRow("PACE_LIMIT", false, 0.3, 1.2, false, "emission"),
                new VetoRow("PACE_LIMIT", true, null, null, null, null));

        List<CalibrationService.VetoPrecision> result = service.vetoPrecision(rows);

        assertThat(result).hasSize(1);
        CalibrationService.VetoPrecision precision = result.get(0);
        assertThat(precision.reasonCode()).isEqualTo("PACE_LIMIT");
        assertThat(precision.n()).isEqualTo(3);
        assertThat(precision.skipped()).isEqualTo(1);
        assertThat(precision.meanHypotheticalR20d()).isCloseTo(0.4, offset(1e-9));
        assertThat(precision.meanHypotheticalR60d()).isCloseTo(1.1, offset(1e-9));
        assertThat(precision.stoppedOutPct()).isCloseTo(50.0, offset(1e-9));
        assertThat(precision.reconstructed()).isEqualTo(0);
        assertThat(precision.reconstructedR20()).isEqualTo(0);
    }
}
