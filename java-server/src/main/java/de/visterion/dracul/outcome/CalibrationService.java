package de.visterion.dracul.outcome;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure math over the raw rows {@link OutcomeLogRepository} pulls out of {@code outcome_log} +
 * {@code decision_log}: Brier calibration, veto precision, hard-exit latency, whipsaw, stop-basis
 * comparison, slippage. No DB access, no LLM calls — read-only analytics.
 */
@Service
public class CalibrationService {

    /** Minimum sample size below which a Brier/calibration result is flagged {@code insufficient}. */
    static final int MIN_SAMPLE = 30;

    private static final double[] BUCKET_EDGES = {0.0, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};

    /** One (predicted confidence, realized win/loss) pair feeding a Brier computation. */
    public record BrierPoint(double predicted, boolean won) {
    }

    /** Brier score + calibration buckets for one unit (the executor, or a single hunter). */
    public record BrierResult(double brier, int n, boolean insufficient, List<Bucket> buckets) {
    }

    /** One non-empty predicted-confidence decile bucket. */
    public record Bucket(String range, int n, double predicted, double observed) {
    }

    /** Flat per-hunter Brier result — {@code agent} alongside the same fields as
     *  {@link BrierResult} (flattened, not nested, to match the API response shape). */
    public record HunterBrier(String agent, double brier, int n, boolean insufficient, List<Bucket> buckets) {
    }

    /** One (hunter agent, predicted confidence, realized triple-barrier label) triple feeding
     *  a per-hunter Brier computation. */
    public record AgentBrierPoint(String agent, double predicted, boolean won) {
    }

    /** One counterfactual row: reason_code, whether it was skipped, and (if not) the
     *  hypothetical outcome. */
    public record VetoRow(String reasonCode, boolean skipped, Double rAfter20d, Double rAfter60d,
            Boolean wouldHaveStoppedOut) {
    }

    public record VetoPrecision(
            @JsonProperty("reason_code") String reasonCode,
            int n, int skipped,
            @JsonProperty("mean_hypothetical_r_20d") double meanHypotheticalR20d,
            @JsonProperty("mean_hypothetical_r_60d") double meanHypotheticalR60d,
            @JsonProperty("stopped_out_pct") double stoppedOutPct) {
    }

    public record LatencyStats(
            int n,
            @JsonProperty("max_seconds") long maxSeconds,
            @JsonProperty("p95_seconds") long p95Seconds) {
    }

    public record WhipsawStats(
            @JsonProperty("reentry_within_10d") long reentryWithin10d,
            @JsonProperty("roundtrip_under_5d") long roundtripUnder5d) {
    }

    /** One TRADE row's whipsaw flags (nullable — older rows may predate the columns). */
    public record WhipsawRowPair(Boolean reentryWithin10d, Boolean roundtripUnder5d) {
    }

    /** One TRADE row: normalized stop basis + its realized/MAE R. */
    public record StopBasisRow(String rawStopBasis, double realizedR, double maeR) {
    }

    public record StopBasisStats(
            String basis, int n,
            @JsonProperty("mean_realized_r") double meanRealizedR,
            @JsonProperty("mean_mae_r") double meanMaeR) {
    }

    public record SlippageStats(int n, double mean, double worst) {
    }

    public static final List<String> BEHAVIOR_CAVEATS = List.of(
            "counterfactuals assume reference-price fills (optimistic)",
            "PACE_LIMIT/BUDGET rejects are opportunity-cost questions",
            "reason_code is the first failed check; stats are conditional on earlier checks passing");

    /** Brier score = mean squared error of (predicted probability − realized 0/1 outcome). */
    public double brier(List<BrierPoint> points) {
        if (points.isEmpty()) return 0.0;
        double sum = 0.0;
        for (BrierPoint p : points) {
            double outcome = p.won() ? 1.0 : 0.0;
            double diff = p.predicted() - outcome;
            sum += diff * diff;
        }
        return sum / points.size();
    }

    public BrierResult brierResult(List<BrierPoint> points) {
        double score = brier(points);
        int n = points.size();
        return new BrierResult(round(score, 4), n, n < MIN_SAMPLE, buckets(points));
    }

    /** Groups per-hunter points by agent and computes an independent {@link BrierResult} for
     *  each, sorted by agent name for a deterministic response order. */
    public List<HunterBrier> hunterBrierResults(List<AgentBrierPoint> points) {
        Map<String, List<BrierPoint>> byAgent = new java.util.TreeMap<>();
        for (AgentBrierPoint p : points) {
            byAgent.computeIfAbsent(p.agent(), k -> new ArrayList<>())
                    .add(new BrierPoint(p.predicted(), p.won()));
        }
        List<HunterBrier> result = new ArrayList<>();
        for (var entry : byAgent.entrySet()) {
            BrierResult r = brierResult(entry.getValue());
            result.add(new HunterBrier(entry.getKey(), r.brier(), r.n(), r.insufficient(), r.buckets()));
        }
        return result;
    }

    List<Bucket> buckets(List<BrierPoint> points) {
        Map<Integer, List<BrierPoint>> byBucket = new LinkedHashMap<>();
        for (BrierPoint p : points) {
            int idx = bucketIndex(p.predicted());
            byBucket.computeIfAbsent(idx, k -> new ArrayList<>()).add(p);
        }
        List<Bucket> result = new ArrayList<>();
        for (int idx = 0; idx < BUCKET_EDGES.length - 1; idx++) {
            List<BrierPoint> in = byBucket.get(idx);
            if (in == null || in.isEmpty()) continue;
            double meanPredicted = in.stream().mapToDouble(BrierPoint::predicted).average().orElse(0);
            double observed = in.stream().mapToDouble(p -> p.won() ? 1.0 : 0.0).average().orElse(0);
            String range = formatRange(BUCKET_EDGES[idx], BUCKET_EDGES[idx + 1]);
            result.add(new Bucket(range, in.size(), round(meanPredicted, 4), round(observed, 4)));
        }
        return result;
    }

    private int bucketIndex(double predicted) {
        for (int idx = BUCKET_EDGES.length - 2; idx >= 0; idx--) {
            if (predicted >= BUCKET_EDGES[idx]) return idx;
        }
        return 0;
    }

    private String formatRange(double lo, double hi) {
        return trimZero(lo) + "-" + trimZero(hi);
    }

    private String trimZero(double v) {
        BigDecimal bd = BigDecimal.valueOf(v).stripTrailingZeros();
        if (bd.scale() < 1) bd = bd.setScale(1);
        return bd.toPlainString();
    }

    /** Groups counterfactual rows by reason_code; means are computed over non-skipped rows,
     *  skipped rows are counted separately (never contribute to the means). */
    public List<VetoPrecision> vetoPrecision(List<VetoRow> rows) {
        Map<String, List<VetoRow>> byReason = new LinkedHashMap<>();
        for (VetoRow r : rows) {
            byReason.computeIfAbsent(r.reasonCode(), k -> new ArrayList<>()).add(r);
        }
        List<VetoPrecision> result = new ArrayList<>();
        for (var entry : byReason.entrySet()) {
            List<VetoRow> group = entry.getValue();
            int n = group.size();
            int skipped = (int) group.stream().filter(VetoRow::skipped).count();
            List<VetoRow> counted = group.stream().filter(r -> !r.skipped()).toList();
            double meanR20 = counted.stream().filter(r -> r.rAfter20d() != null)
                    .mapToDouble(VetoRow::rAfter20d).average().orElse(0);
            double meanR60 = counted.stream().filter(r -> r.rAfter60d() != null)
                    .mapToDouble(VetoRow::rAfter60d).average().orElse(0);
            long stoppedOutKnown = counted.stream().filter(r -> r.wouldHaveStoppedOut() != null).count();
            long stoppedOutTrue = counted.stream()
                    .filter(r -> Boolean.TRUE.equals(r.wouldHaveStoppedOut())).count();
            double stoppedOutPct = stoppedOutKnown == 0 ? 0.0 : (100.0 * stoppedOutTrue / stoppedOutKnown);
            result.add(new VetoPrecision(entry.getKey(), n, skipped, round(meanR20, 4), round(meanR60, 4),
                    round(stoppedOutPct, 2)));
        }
        return result;
    }

    public LatencyStats latency(List<Long> secondsList) {
        if (secondsList.isEmpty()) return new LatencyStats(0, 0, 0);
        List<Long> sorted = new ArrayList<>(secondsList);
        sorted.sort(Long::compareTo);
        long max = sorted.get(sorted.size() - 1);
        long p95 = percentile(sorted, 95);
        return new LatencyStats(sorted.size(), max, p95);
    }

    private long percentile(List<Long> sorted, int pct) {
        if (sorted.size() == 1) return sorted.get(0);
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }

    public WhipsawStats whipsaw(List<WhipsawRowPair> rows) {
        long reentry = rows.stream().filter(r -> Boolean.TRUE.equals(r.reentryWithin10d())).count();
        long roundtrip = rows.stream().filter(r -> Boolean.TRUE.equals(r.roundtripUnder5d())).count();
        return new WhipsawStats(reentry, roundtrip);
    }

    /** Normalizes a free-text stop_basis string to ATR/SWING_LOW/OTHER by substring match —
     *  strings containing "swing_low" win over "ATR" (the anchor text records both when
     *  swing_low was the wider/winning anchor). */
    public String normalizeStopBasis(String raw) {
        if (raw == null) return "OTHER";
        String lower = raw.toLowerCase();
        if (lower.contains("swing_low")) return "SWING_LOW";
        if (lower.contains("atr")) return "ATR";
        return "OTHER";
    }

    public List<StopBasisStats> stopBasisStats(List<StopBasisRow> rows) {
        Map<String, List<StopBasisRow>> byBasis = new LinkedHashMap<>();
        for (StopBasisRow r : rows) {
            byBasis.computeIfAbsent(normalizeStopBasis(r.rawStopBasis()), k -> new ArrayList<>()).add(r);
        }
        List<StopBasisStats> result = new ArrayList<>();
        for (var entry : byBasis.entrySet()) {
            List<StopBasisRow> group = entry.getValue();
            double meanR = group.stream().mapToDouble(StopBasisRow::realizedR).average().orElse(0);
            double meanMae = group.stream().mapToDouble(StopBasisRow::maeR).average().orElse(0);
            result.add(new StopBasisStats(entry.getKey(), group.size(), round(meanR, 4), round(meanMae, 4)));
        }
        return result;
    }

    public SlippageStats slippage(List<Double> values) {
        if (values.isEmpty()) return new SlippageStats(0, 0.0, 0.0);
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double worst = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        return new SlippageStats(values.size(), round(mean, 4), round(worst, 4));
    }

    private double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
