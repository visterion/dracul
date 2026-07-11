package de.visterion.dracul.vistierie;

import de.visterion.dracul.strigoi.StrigoiDetail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Aggregates the cost/budget panel data served at {@code /api/vistierie}.
 *
 * <p>The panel needs data from several Vistierie endpoints (routing rules, the
 * agent list, per-agent detail, and the cost dashboard). Fetched serially these
 * are ~15 blocking HTTP round-trips (one detail call <em>per</em> strigoi), which
 * dominated the Chronicle view load at ~2s. We fan the calls out across virtual
 * threads and cache the assembled result for a short TTL, since cost figures do
 * not change second-to-second.
 */
@Service
public class VistierieDataService {

    private final VistierieProperties props;
    private final VistierieClient client;
    private final long cacheTtlNanos;

    private final AtomicReference<Cached> cache = new AtomicReference<>();

    private record Cached(VistierieData data, long expiresAtNanos) {}

    public VistierieDataService(
            VistierieProperties props,
            VistierieClient client,
            @Value("${dracul.vistierie.cache-ttl-seconds:30}") long cacheTtlSeconds) {
        this.props = props;
        this.client = client;
        this.cacheTtlNanos = Math.max(0, cacheTtlSeconds) * 1_000_000_000L;
    }

    public VistierieData getData() {
        if (cacheTtlNanos > 0) {
            var cached = cache.get();
            if (cached != null && System.nanoTime() < cached.expiresAtNanos()) {
                return cached.data();
            }
        }
        var data = aggregate();
        if (cacheTtlNanos > 0) {
            cache.set(new Cached(data, System.nanoTime() + cacheTtlNanos));
        }
        return data;
    }

    private VistierieData aggregate() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Phase 1 — independent top-level calls run concurrently.
            Future<List<LlmProvider>> providersF = executor.submit(client::getProviders);
            Future<List<StrigoiStatus>> strigoiF = executor.submit(client::listStrigoi);
            Future<List<VistierieData.DailySpend>> dailyF = executor.submit(client::getDashboardData);

            List<LlmProvider> providers = providersF.get();
            List<StrigoiStatus> strigoi = strigoiF.get();

            double totalCostUsd = providers.stream().mapToDouble(LlmProvider::todayCostUsd).sum();

            // Tiers: budgets from config, usedUsd approximated as total provider cost split
            // proportionally to each tier's share of the total budget (real per-tier tracking
            // requires a Vistierie API that does not exist yet).
            double totalTierBudget = props.tiers().stream()
                    .mapToDouble(VistierieProperties.TierConfig::budgetUsd).sum();
            double finalTotalCostUsd = totalCostUsd;
            var tiers = props.tiers().stream()
                    .map(t -> new VistierieData.TierBudget(t.name(), t.models(), t.budgetUsd(),
                            totalTierBudget > 0
                                    ? Math.min(finalTotalCostUsd * (t.budgetUsd() / totalTierBudget), t.budgetUsd())
                                    : 0.0))
                    .toList();

            // Agent spending: from the Vistierie cost endpoint (grouped by agent),
            // falling back to the per-strigoi detail fan-out for older Vistierie
            // versions that don't support group_by=agent yet.
            var agentSpends = agentSpends(executor, strigoi);
            double agentTotal = agentSpends.stream().mapToDouble(VistierieData.AgentSpend::totalUsd).sum();
            agentTotal = Math.max(agentTotal, 0.01); // avoid division by zero

            final double finalTotal = agentTotal;
            var agentSpendsWithPct = agentSpends.stream()
                    .map(a -> new VistierieData.AgentSpend(a.agent(), a.totalUsd(),
                            (int) Math.round(a.totalUsd() / finalTotal * 100)))
                    .toList();

            var daily = dailyF.get();
            double monthlyTotalUsd = daily.stream().mapToDouble(VistierieData.DailySpend::totalUsd).sum();

            return new VistierieData(tiers, agentSpendsWithPct, daily, monthlyTotalUsd, props.monthlyBudgetUsd());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while aggregating Vistierie data", e);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException("Failed to aggregate Vistierie data", cause);
        }
    }

    private List<VistierieData.AgentSpend> agentSpends(
            java.util.concurrent.ExecutorService executor, List<StrigoiStatus> strigoi)
            throws InterruptedException, ExecutionException {
        var monthStart = java.time.LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        java.util.Map<String, Long> costByAgent;
        try {
            costByAgent = client.getCostByAgent(monthStart);
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
            // Older Vistierie without group_by=agent: keep the per-strigoi detail path.
            return legacyAgentSpends(executor, strigoi);
        }
        var spends = new ArrayList<VistierieData.AgentSpend>();
        var remaining = new java.util.LinkedHashMap<>(costByAgent);
        for (var s : strigoi) {                       // strigoi order preserved; missing -> 0
            Long micros = remaining.remove(s.name());
            spends.add(new VistierieData.AgentSpend(s.name(), micros == null ? 0.0 : micros / 1_000_000.0, 0));
        }
        remaining.forEach((agent, micros) ->          // voievod, daywalker, "(unattributed)", …
                spends.add(new VistierieData.AgentSpend(agent, micros / 1_000_000.0, 0)));
        return spends;
    }

    private List<VistierieData.AgentSpend> legacyAgentSpends(
            java.util.concurrent.ExecutorService executor, List<StrigoiStatus> strigoi)
            throws InterruptedException, ExecutionException {
        var detailFutures = new ArrayList<Map.Entry<String, Future<Optional<StrigoiDetail>>>>();
        for (var s : strigoi) {
            detailFutures.add(Map.entry(s.name(),
                    executor.submit(() -> client.getStrigoiDetail(s.name()))));
        }
        var spends = new ArrayList<VistierieData.AgentSpend>();
        var seen = new java.util.HashSet<String>();
        for (var entry : detailFutures) {
            if (!seen.add(entry.getKey())) continue;  // dedup by name
            double cost = entry.getValue().get().map(d -> d.configuration().dailyUsedUsd()).orElse(0.0);
            spends.add(new VistierieData.AgentSpend(entry.getKey(), cost, 0));
        }
        for (var name : List.of("voievod", "daywalker")) {   // append only when missing
            if (seen.add(name)) spends.add(new VistierieData.AgentSpend(name, 0.0, 0));
        }
        return spends;
    }
}
