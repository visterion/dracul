package de.visterion.dracul.vistierie;

import de.visterion.dracul.strigoi.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Profile("dev")
public class MockVistierieClient implements VistierieClient {

    private static final String MINUS_22M = Instant.now().minus(22, ChronoUnit.MINUTES).toString();
    private static final String PLUS_22H  = Instant.now().plus(22, ChronoUnit.HOURS).toString();
    private static final String MINUS_1D  = Instant.now().minus(1, ChronoUnit.DAYS).toString();
    private static final String PLUS_18H  = Instant.now().plus(18, ChronoUnit.HOURS).toString();
    private static final String MINUS_12H = Instant.now().minus(12, ChronoUnit.HOURS).toString();
    private static final String PLUS_20H  = Instant.now().plus(20, ChronoUnit.HOURS).toString();

    @Override
    public List<StrigoiStatus> listStrigoi() {
        return List.of(
                new StrigoiStatus("strigoi-spin",    "hunting",    MINUS_22M, null),
                new StrigoiStatus("strigoi-insider",  "resting",   MINUS_1D,  PLUS_18H),
                new StrigoiStatus("strigoi-echo",     "hunting",   MINUS_12H, null),
                new StrigoiStatus("strigoi-lazarus",  "paused",    null,      null),
                new StrigoiStatus("strigoi-index",    "resting",   null,      PLUS_20H),
                new StrigoiStatus("strigoi-merger",   "budget-hit",null,      null)
        );
    }

    @Override
    public Optional<StrigoiDetail> getStrigoiDetail(String name) {
        return Optional.ofNullable(DETAILS.get(name));
    }

    @Override
    public double getTodayCostUsd() {
        return 0.43;
    }

    @Override
    public List<LlmProvider> getProviders() {
        return List.of(
                new LlmProvider("anthropic", "Anthropic", "connected", "··· 4f3a", null,
                        List.of("claude-sonnet-4-6", "claude-opus-4-7", "claude-haiku-4-5"),
                        12453L, 3201L, 0.43, null),
                new LlmProvider("openai", "OpenAI", "fallback", "··· 8c12", null,
                        List.of("gpt-4o", "gpt-4o-mini"),
                        0L, 0L, 0.0, null),
                new LlmProvider("ollama", "Ollama (Local)", "local", null,
                        "http://localhost:11434",
                        List.of("qwen2.5:14b", "llama3.2:8b", "nomic-embed-text"),
                        0L, 0L, 0.0, 47)
        );
    }

    @Override
    public List<VistierieData.DailySpend> getDashboardData() {
        var result = new ArrayList<VistierieData.DailySpend>();
        var today = LocalDate.now();
        for (int i = 29; i >= 0; i--) {
            var date = today.minusDays(i).toString();
            // generate realistic variance between 0.10 and 0.80
            double base = 0.35 + 0.20 * Math.sin(i * 0.4);
            double noise = 0.08 * Math.sin(i * 1.7 + 0.5);
            double val = Math.round((base + noise) * 100.0) / 100.0;
            result.add(new VistierieData.DailySpend(date, val));
        }
        return result;
    }

    private static final List<TraceEvent> SPIN_TRACE = List.of(
            new TraceEvent("00:00", "start",    "▼ awakened (scheduled)"),
            new TraceEvent("00:00", "info",     "pre-screening 47 candidates"),
            new TraceEvent("00:01", "info",     "qualified: 6 candidates"),
            new TraceEvent("00:01", "llm-call", "llm call: anthropic/claude-sonnet-4 · 2,453 tokens"),
            new TraceEvent("00:04", "info",     "response received (3.2s)"),
            new TraceEvent("00:04", "info",     "parsed 4 prey"),
            new TraceEvent("00:04", "end",      "▲ completed · 4 prey · $0.012")
    );

    private static final StrigoiConfiguration SPIN_CFG = new StrigoiConfiguration(
            "0 22 * * 1-5", PLUS_22H, false, "Reasoning",
            List.of("anthropic/claude-sonnet-4", "anthropic/claude-opus-4"),
            1.00, 0.03, 25.00, 4.40, "anthropic", "openai"
    );

    private static final StrigoiDetail SPIN_DETAIL = new StrigoiDetail(
            "strigoi-spin", "SPIN", "Hunter of spin-offs and forced selling anomalies",
            "After Greenblatt, 1997", "hunting", MINUS_22M, PLUS_22H,
            23, 30, 2.4, 0.67, 14, 21,
            List.of(
                    new RunEntry("run-spin-1", MINUS_22M, 4, 0.012, "anthropic/claude-sonnet-4", SPIN_TRACE),
                    new RunEntry("run-spin-2", MINUS_1D, 2, 0.009, "anthropic/claude-sonnet-4", List.of()),
                    new RunEntry("run-spin-3", Instant.now().minus(2, ChronoUnit.DAYS).toString(), 0, 0.007, "anthropic/claude-sonnet-4", List.of()),
                    new RunEntry("run-spin-4", Instant.now().minus(3, ChronoUnit.DAYS).toString(), 3, 0.011, "anthropic/claude-sonnet-4", List.of()),
                    new RunEntry("run-spin-5", Instant.now().minus(4, ChronoUnit.DAYS).toString(), 1, 0.008, "anthropic/claude-sonnet-4", List.of())
            ),
            List.of(),  // recentPrey filled from DB by StrigoiController
            SPIN_CFG,
            weeklyPerf(0.67, 0.08)
    );

    private static final StrigoiConfiguration INSIDER_CFG = new StrigoiConfiguration(
            "0 21 * * 1-5", PLUS_18H, false, "Reasoning",
            List.of("anthropic/claude-sonnet-4"),
            1.00, 0.00, 20.00, 3.20, "anthropic", null
    );

    private static final StrigoiDetail INSIDER_DETAIL = new StrigoiDetail(
            "strigoi-insider", "INSIDER", "Hunter of insider cluster buying anomalies",
            "After Jeng, Metrick & Zeckhauser, 2003", "resting", MINUS_1D, PLUS_18H,
            20, 22, 1.8, 0.71, 17, 24,
            List.of(
                    new RunEntry("run-insider-1", MINUS_1D, 2, 0.009, "anthropic/claude-sonnet-4", List.of()),
                    new RunEntry("run-insider-2", Instant.now().minus(2, ChronoUnit.DAYS).toString(), 1, 0.008, "anthropic/claude-sonnet-4", List.of())
            ),
            List.of(),
            INSIDER_CFG,
            weeklyPerf(0.71, 0.06)
    );

    private static final StrigoiConfiguration ECHO_CFG = new StrigoiConfiguration(
            "0 20 * * 2,4", PLUS_20H, false, "Reasoning",
            List.of("anthropic/claude-sonnet-4", "anthropic/claude-haiku-4-5"),
            0.75, 0.01, 15.00, 2.80, "anthropic", "openai"
    );

    private static final StrigoiDetail ECHO_DETAIL = new StrigoiDetail(
            "strigoi-echo", "PEAD", "Hunter of post-earnings announcement drift",
            "After Bernard & Thomas, 1989", "resting", MINUS_12H, PLUS_20H,
            18, 22, 2.1, 0.54, 12, 22,
            List.of(
                    new RunEntry("run-echo-1", MINUS_12H, 3, 0.011, "anthropic/claude-sonnet-4", List.of())
            ),
            List.of(),
            ECHO_CFG,
            weeklyPerf(0.54, 0.07)
    );

    private static final Map<String, StrigoiDetail> DETAILS = Map.of(
            "strigoi-spin",    SPIN_DETAIL,
            "strigoi-insider", INSIDER_DETAIL,
            "strigoi-echo",    ECHO_DETAIL
    );

    private static List<WeeklyPerformance> weeklyPerf(double base, double variance) {
        var weeks = List.of("Nov 25","Dec 2","Dec 9","Dec 16","Dec 23","Dec 30",
                "Jan 6","Jan 13","Jan 20","Jan 27","Feb 3","Feb 10","Feb 17","Feb 24",
                "Mar 3","Mar 10","Mar 17","Mar 24","Mar 31","Apr 7","Apr 14","Apr 21",
                "Apr 28","May 5","May 12");
        var result = new ArrayList<WeeklyPerformance>();
        for (int i = 0; i < weeks.size(); i++) {
            double hr = Math.min(0.95, Math.max(0.3, base + Math.sin(i * 0.7) * variance));
            int pc = Math.max(1, (int) Math.round(4 + Math.sin(i * 0.9) * 2));
            result.add(new WeeklyPerformance(weeks.get(i), hr, pc));
        }
        return result;
    }
}
