package de.visterion.dracul.vistierie;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.visterion.dracul.strigoi.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.*;

@Component
@Profile("!dev")
public class HttpVistierieClient implements VistierieClient {

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public HttpVistierieClient(
            @Value("${dracul.vistierie.url}") String baseUrl,
            ObjectMapper mapper) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Tenant-Id", "dracul")
                .build();
        this.mapper = mapper;
    }

    @Override
    public List<StrigoiStatus> listStrigoi() {
        var body = restClient.get().uri("/agents").retrieve().body(JsonNode.class);
        if (body == null || !body.isArray()) return List.of();
        var result = new ArrayList<StrigoiStatus>();
        for (var node : body) {
            var state = node.path("paused").asBoolean(false) ? "paused" : "resting";
            result.add(new StrigoiStatus(
                    node.path("name").asText(),
                    state,
                    node.path("last_tick_at").isNull() ? null : node.path("last_tick_at").asText(),
                    null
            ));
        }
        return result;
    }

    @Override
    public Optional<StrigoiDetail> getStrigoiDetail(String name) {
        try {
            var agentNode = restClient.get().uri("/agents/{name}", name).retrieve().body(JsonNode.class);
            if (agentNode == null) return Optional.empty();
            var runsNode = restClient.get()
                    .uri("/admin/runs?agent={name}&limit=5", name)
                    .retrieve().body(JsonNode.class);

            var runs = new ArrayList<RunEntry>();
            if (runsNode != null && runsNode.isArray()) {
                for (var run : runsNode) {
                    runs.add(new RunEntry(
                            run.path("id").asText(),
                            run.path("started_at").asText(),
                            run.path("prey_count").asInt(0),
                            run.path("cost_usd").asDouble(0),
                            run.path("model").asText(),
                            List.of()
                    ));
                }
            }

            var cfg = agentNode.path("configuration");
            var config = new StrigoiConfiguration(
                    cfg.path("cron").asText(),
                    cfg.path("next_run_at").asText(""),
                    cfg.path("disabled").asBoolean(false),
                    cfg.path("tier").asText("Standard"),
                    List.of(),
                    cfg.path("daily_budget_usd").asDouble(0),
                    cfg.path("daily_used_usd").asDouble(0),
                    cfg.path("monthly_budget_usd").asDouble(0),
                    cfg.path("monthly_used_usd").asDouble(0),
                    cfg.path("primary_provider").asText("anthropic"),
                    cfg.path("fallback_provider").isNull() ? null : cfg.path("fallback_provider").asText()
            );

            return Optional.of(new StrigoiDetail(
                    agentNode.path("name").asText(),
                    agentNode.path("anomaly_type").asText(),
                    agentNode.path("description").asText(""),
                    agentNode.path("reference").asText(""),
                    agentNode.path("paused").asBoolean(false) ? "paused" : "resting",
                    agentNode.path("last_tick_at").asText(""),
                    config.nextRunAt(),
                    agentNode.path("hunts_this_month").asInt(0),
                    agentNode.path("scheduled_hunts_this_month").asInt(0),
                    agentNode.path("avg_prey_per_hunt").asDouble(0),
                    agentNode.path("hit_rate_90d").asDouble(0),
                    0, 0,
                    runs, List.of(), config, List.of()
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public double getTodayCostUsd() {
        try {
            var today = java.time.LocalDate.now() + "T00:00:00Z";
            var body = restClient.get()
                    .uri("/admin/cost?granularity=day&from={from}&group_by=tenant", today)
                    .retrieve().body(JsonNode.class);
            if (body == null) return 0.0;
            double total = 0.0;
            if (body.isArray()) {
                for (var row : body) total += row.path("cost_eur").asDouble(0);
            } else {
                total = body.path("cost_eur").asDouble(0);
            }
            return total;
        } catch (Exception e) {
            return 0.0;
        }
    }

    @Override
    public List<LlmProvider> getProviders() {
        try {
            var body = restClient.get().uri("/admin/routing-rules").retrieve().body(JsonNode.class);
            if (body == null || !body.isArray()) return List.of();
            var seen = new LinkedHashSet<String>();
            var result = new ArrayList<LlmProvider>();
            for (var rule : body) {
                var provider = rule.path("provider").asText();
                if (seen.add(provider)) {
                    result.add(new LlmProvider(
                            provider, capitalize(provider),
                            "connected", null, null,
                            List.of(), 0L, 0L, 0.0, null
                    ));
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<VistierieData.DailySpend> getDashboardData() {
        var result = new ArrayList<VistierieData.DailySpend>();
        var today = LocalDate.now();
        for (int i = 29; i >= 0; i--) {
            result.add(new VistierieData.DailySpend(today.minusDays(i).toString(), 0.0));
        }
        return result;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
