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

    // ── agent control ─────────────────────────────────────────────
    void patchAgent(String name, boolean paused);

    // ── runs ──────────────────────────────────────────────────────
    List<VistierieRunDetail> listRuns();
    VistierieRunDetail triggerRun(String agentName);
    List<VistierieRunEvent> getRunEvents(String runId);

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
