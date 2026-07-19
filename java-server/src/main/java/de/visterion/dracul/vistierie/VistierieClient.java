package de.visterion.dracul.vistierie;

import de.visterion.dracul.strigoi.StrigoiDetail;
import java.util.List;
import java.util.Optional;

public interface VistierieClient {
    // ── existing ──────────────────────────────────────────────────
    List<StrigoiStatus> listStrigoi();
    Optional<StrigoiDetail> getStrigoiDetail(String name);
    double getTodayCostUsd();
    List<LlmProvider> getProviders();
    List<VistierieData.DailySpend> getDashboardData();
    java.util.Map<String, Long> getCostByAgent(java.time.Instant from);

    // ── agent control ─────────────────────────────────────────────
    void patchAgent(String name, boolean paused);

    // ── runs ──────────────────────────────────────────────────────
    List<VistierieRunDetail> listRuns();

    /** Triggers a run with no input payload. */
    default VistierieRunDetail triggerRun(String agentName) {
        return triggerRun(agentName, null);
    }

    /** Triggers a run, forwarding {@code input} as the run's {@code payload}
     *  (Vistierie contract: {@code POST /agents/{name}/run} body {@code {"payload": ...}}).
     *  {@code input} may be {@code null} for a payload-less trigger. */
    default VistierieRunDetail triggerRun(String agentName, java.util.Map<String, Object> input) {
        return triggerRun(agentName, input, null, null);
    }

    /**
     * Triggers a run and — when {@code completionWebhook} is non-null — sends
     * {@code completion_webhook} + {@code completion_webhook_token} in the run body.
     * Vistierie's {@code POST /agents/{name}/run} path does NOT fall back to the agent
     * definition's registered webhook (unlike its cron and streaming dispatch paths),
     * so any triggered run whose completion must reach Dracul MUST pass both here (R3).
     */
    VistierieRunDetail triggerRun(String agentName, java.util.Map<String, Object> input,
            String completionWebhook, String completionWebhookToken);

    List<VistierieRunEvent> getRunEvents(String runId);
    java.util.List<RunSearchHit> searchRuns(String agent, String q, Boolean hasError,
            java.util.List<String> status, java.time.Instant from, java.time.Instant to,
            int limit, int offset);

    /** Runs for the operator's activity inspector, newest first. {@code agent} null = all agents.
     *  Backed by tenant-scoped {@code /runs}, dracul-only; agent-filter is client-side
     *  because {@code /runs} ignores the agent param. */
    java.util.List<RunSearchHit> listAgentRuns(String agent, int limit, int offset);

    tools.jackson.databind.JsonNode getRunTranscript(String runId, String view);
    tools.jackson.databind.JsonNode getRunToolCall(String runId, String toolUseId);

    // ── budget ────────────────────────────────────────────────────
    BudgetStatus getTenantBudget();
    BudgetStatus patchTenantBudget(BudgetPatch patch);
    BudgetStatus getAgentBudget(String agentName);
    BudgetStatus patchAgentBudget(String agentName, BudgetPatch patch);

    // ── kill switch ───────────────────────────────────────────────
    KillStatus getKillStatus();
    void setKill(String reason);
    void clearKill();

    // ── agent registration ────────────────────────────────────────
    Optional<AgentDetail> getAgent(String name);

    AgentDetail registerAgent(CreateAgentRequest req);

    AgentDetail updateAgent(String name, UpdateAgentRequest req);
}
