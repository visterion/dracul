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

    private final java.util.Map<String, AgentDetail> agents = new java.util.concurrent.ConcurrentHashMap<>();

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

    @Override
    public java.util.Map<String, Long> getCostByAgent(Instant from) {
        var m = new java.util.LinkedHashMap<String, Long>();
        m.put("strigoi-spin", 800_000L);
        m.put("strigoi-insider", 400_000L);
        m.put("voievod", 240_000L);
        m.put("daywalker", 160_000L);
        m.put("(unattributed)", 20_000L);
        return m;
    }

    @Override
    public void patchAgent(String name, boolean paused) { /* no-op in mock */ }

    @Override
    public List<VistierieRunDetail> listRuns() {
        return List.of(
            new VistierieRunDetail("run-mock-1", "strigoi-spin", "done",
                Instant.now().minus(1, ChronoUnit.HOURS).toString(),
                Instant.now().minus(55, ChronoUnit.MINUTES).toString(),
                "4 prey found", null),
            new VistierieRunDetail("run-mock-2", "strigoi-insider", "done",
                Instant.now().minus(2, ChronoUnit.HOURS).toString(),
                Instant.now().minus(115, ChronoUnit.MINUTES).toString(),
                "2 prey found", null)
        );
    }

    @Override
    public VistierieRunDetail triggerRun(String agentName) {
        return new VistierieRunDetail("run-mock-triggered", agentName, "running",
            Instant.now().toString(), null, null, null);
    }

    @Override
    public List<VistierieRunEvent> getRunEvents(String runId) { return List.of(); }

    @Override
    public BudgetStatus getTenantBudget() {
        return new BudgetStatus(
            5_000_000L, 150_000_000L, 80, 80,
            430_000L, 12_500_000L, false, false, false, false
        );
    }

    @Override
    public BudgetStatus patchTenantBudget(BudgetPatch patch) {
        long daily   = patch.dailyCapMicros()   != null ? patch.dailyCapMicros()   : 5_000_000L;
        long monthly = patch.monthlyCapMicros()  != null ? patch.monthlyCapMicros() : 150_000_000L;
        int dw = patch.dailyWarnPercent()   != null ? patch.dailyWarnPercent()  : 80;
        int mw = patch.monthlyWarnPercent() != null ? patch.monthlyWarnPercent() : 80;
        return new BudgetStatus(daily, monthly, dw, mw, 430_000L, 12_500_000L, false, false, false, false);
    }

    @Override
    public BudgetStatus getAgentBudget(String agentName) {
        return switch (agentName) {
            case "strigoi-spin"    -> new BudgetStatus(1_000_000L, 25_000_000L, 80, 80,  30_000L,  440_000L, false, false, false, false);
            case "strigoi-insider" -> new BudgetStatus(1_000_000L, 20_000_000L, 80, 80,       0L,        0L, false, false, false, false);
            case "strigoi-echo"    -> new BudgetStatus(  750_000L, 15_000_000L, 80, 80,  10_000L,  280_000L, false, false, false, false);
            case "strigoi-lazarus" -> new BudgetStatus(  500_000L, 10_000_000L, 80, 80,       0L,        0L, false, false, false, false);
            case "strigoi-index"   -> new BudgetStatus(  500_000L, 10_000_000L, 80, 80,       0L,        0L, false, false, false, false);
            case "strigoi-merger"  -> new BudgetStatus(  500_000L, 10_000_000L, 80, 80,       0L,        0L, false, false, false, false);
            default                -> BudgetStatus.empty();
        };
    }

    @Override
    public BudgetStatus patchAgentBudget(String agentName, BudgetPatch patch) {
        return getAgentBudget(agentName);
    }

    @Override
    public KillStatus getKillStatus() { return new KillStatus(null, null, null); }

    @Override
    public void setKill(String reason) { /* no-op */ }

    @Override
    public void clearKill() { /* no-op */ }

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

    @Override
    public java.util.List<RunSearchHit> searchRuns(String agent, String q, Boolean hasError,
            java.util.List<String> status, java.time.Instant from, java.time.Instant to,
            int limit, int offset) {
        return java.util.List.of();
    }

    @Override
    public tools.jackson.databind.JsonNode getRunTranscript(String runId, String view) {
        return null;
    }

    @Override
    public tools.jackson.databind.JsonNode getRunToolCall(String runId, String toolUseId) {
        return null;
    }

    @Override
    public java.util.Optional<AgentDetail> getAgent(String name) {
        return java.util.Optional.ofNullable(agents.get(name));
    }

    @Override
    public AgentDetail registerAgent(CreateAgentRequest req) {
        var now = java.time.Instant.now();
        var d = new AgentDetail(
                java.util.UUID.randomUUID().toString(),
                req.name(),
                req.system_prompt(),
                req.model_purpose(),
                req.tools(),
                req.output_schema(),
                req.max_turns() == null ? 25 : req.max_turns(),
                req.max_run_seconds() == null ? 1800 : req.max_run_seconds(),
                false, 1, now, now,
                req.schedule(), null,
                req.completion_webhook(), req.completion_webhook_token(),
                req.event_source_url(), req.session_duration_seconds(), req.poll_interval_seconds());
        agents.put(req.name(), d);
        return d;
    }

    @Override
    public AgentDetail updateAgent(String name, UpdateAgentRequest req) {
        var existing = agents.get(name);
        if (existing == null) throw new RuntimeException("agent not found: " + name);
        var updated = new AgentDetail(
                existing.id(), existing.name(),
                req.system_prompt(), req.model_purpose(),
                req.tools(), req.output_schema(),
                req.max_turns() == null ? 25 : req.max_turns(),
                req.max_run_seconds() == null ? 1800 : req.max_run_seconds(),
                existing.paused(), existing.version() + 1,
                existing.created_at(), java.time.Instant.now(),
                req.schedule(), existing.last_tick_at(),
                req.completion_webhook(), req.completion_webhook_token(),
                req.event_source_url(), req.session_duration_seconds(), req.poll_interval_seconds());
        agents.put(name, updated);
        return updated;
    }

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
