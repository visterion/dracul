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

            // Phase 2 — one detail call per strigoi, all in flight at once.
            var detailFutures = new ArrayList<Map.Entry<String, Future<Optional<StrigoiDetail>>>>();
            for (var s : strigoi) {
                detailFutures.add(Map.entry(s.name(),
                        executor.submit(() -> client.getStrigoiDetail(s.name()))));
            }

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

            // Agent spending: from strigoi detail configurations (order preserved).
            var agentSpends = new ArrayList<VistierieData.AgentSpend>();
            double agentTotal = 0.0;
            for (var entry : detailFutures) {
                Optional<StrigoiDetail> detail = entry.getValue().get();
                double cost = detail.map(d -> d.configuration().dailyUsedUsd()).orElse(0.0);
                agentSpends.add(new VistierieData.AgentSpend(entry.getKey(), cost, 0));
                agentTotal += cost;
            }
            // Add non-strigoi agents.
            agentSpends.add(new VistierieData.AgentSpend("voievod", 0.0, 0));
            agentSpends.add(new VistierieData.AgentSpend("daywalker", 0.0, 0));
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
}
