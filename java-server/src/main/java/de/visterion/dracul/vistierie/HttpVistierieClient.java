package de.visterion.dracul.vistierie;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.visterion.dracul.strigoi.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@Component
@Profile("!dev")
public class HttpVistierieClient implements VistierieClient {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(HttpVistierieClient.class);

    private final RestClient tenantClient;
    private final RestClient adminClient;
    private final ObjectMapper mapper;

    public HttpVistierieClient(
            @Value("${dracul.vistierie.url}") String baseUrl,
            @Value("${dracul.vistierie.tenant-token:}") String tenantToken,
            @Value("${dracul.vistierie.admin-token:}") String adminToken,
            ObjectMapper mapper) {
        this.tenantClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + tenantToken)
                .defaultHeader("X-Tenant-Id", "dracul")
                .build();
        this.adminClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + adminToken)
                .build();
        this.mapper = mapper;
        if (tenantToken.isBlank() || adminToken.isBlank()) {
            log.warn("Vistierie tokens are blank (tenant-token blank={}, admin-token blank={}); "
                    + "requests to Vistierie will be unauthenticated and 401. "
                    + "Set VISTIERIE_TENANT_TOKEN and VISTIERIE_ADMIN_TOKEN.",
                    tenantToken.isBlank(), adminToken.isBlank());
        }
    }

    /** Package-private constructor for unit tests: accepts pre-built RestClients. */
    HttpVistierieClient(RestClient tenantClient, RestClient adminClient, ObjectMapper mapper) {
        this.tenantClient = tenantClient;
        this.adminClient = adminClient;
        this.mapper = mapper;
    }

    @Override
    public List<StrigoiStatus> listStrigoi() {
        var body = tenantClient.get().uri("/agents").retrieve().body(JsonNode.class);
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
            var agentNode = tenantClient.get().uri("/agents/{name}", name).retrieve().body(JsonNode.class);
            if (agentNode == null) return Optional.empty();
            var runsNode = adminClient.get()
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
            var today = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toString();
            var body = adminClient.get()
                    .uri("/admin/cost?granularity=day&from={from}&tenant=dracul", today)
                    .retrieve().body(JsonNode.class);
            if (body == null) return 0.0;
            double total = 0.0;
            for (var bucket : body.path("buckets")) {
                for (var g : bucket.path("groups")) {
                    total += g.path("cost_micros").asDouble(0) / 1_000_000.0;
                }
            }
            return total;
        } catch (Exception e) {
            return 0.0;
        }
    }

    @Override
    public List<LlmProvider> getProviders() {
        try {
            var body = adminClient.get().uri("/admin/routing-rules").retrieve().body(JsonNode.class);
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
        try {
            var from = LocalDate.now().minusDays(29)
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toString();
            var body = adminClient.get()
                    .uri("/admin/cost?granularity=day&from={from}&tenant=dracul", from)
                    .retrieve().body(JsonNode.class);
            if (body == null) return zeroSpend();
            var byDate = new java.util.TreeMap<String, Double>();
            var buckets = body.path("buckets");
            if (buckets.isArray()) {
                for (var bucket : buckets) {
                    var ts = bucket.path("ts").asText();
                    var date = ts.length() >= 10 ? ts.substring(0, 10) : ts;
                    double cost = 0.0;
                    var groups = bucket.path("groups");
                    if (groups.isArray()) {
                        for (var g : groups) cost += g.path("cost_micros").asDouble(0) / 1_000_000.0;
                    }
                    byDate.merge(date, cost, Double::sum);
                }
            }
            var today = LocalDate.now();
            var result = new ArrayList<VistierieData.DailySpend>();
            for (int i = 29; i >= 0; i--) {
                var date = today.minusDays(i).toString();
                result.add(new VistierieData.DailySpend(date, byDate.getOrDefault(date, 0.0)));
            }
            return result;
        } catch (Exception e) {
            return zeroSpend();
        }
    }

    private List<VistierieData.DailySpend> zeroSpend() {
        var result = new ArrayList<VistierieData.DailySpend>();
        var today = LocalDate.now();
        for (int i = 29; i >= 0; i--)
            result.add(new VistierieData.DailySpend(today.minusDays(i).toString(), 0.0));
        return result;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public void patchAgent(String name, boolean paused) {
        tenantClient.patch().uri("/agents/{name}", name)
            .body(java.util.Map.of("paused", paused))
            .retrieve().toBodilessEntity();
    }

    @Override
    public List<VistierieRunDetail> listRuns() {
        try {
            var body = tenantClient.get().uri("/runs").retrieve().body(JsonNode.class);
            if (body == null || !body.isArray()) return List.of();
            var result = new ArrayList<VistierieRunDetail>();
            for (var node : body) {
                result.add(new VistierieRunDetail(
                    node.path("run_id").asText(),
                    node.path("agent_name").asText(),
                    node.path("status").asText(),
                    node.path("started_at").isNull() ? null : node.path("started_at").asText(),
                    node.path("finished_at").isNull() ? null : node.path("finished_at").asText(),
                    node.path("summary").isNull() ? null : node.path("summary").asText(),
                    node.path("error").isNull() ? null : node.path("error").asText()
                ));
            }
            return result;
        } catch (Exception e) { return List.of(); }
    }

    @Override
    public VistierieRunDetail triggerRun(String agentName) {
        var body = tenantClient.post().uri("/agents/{name}/run", agentName)
            .body(java.util.Map.of())
            .retrieve().body(JsonNode.class);
        if (body == null) throw new RuntimeException("triggerRun returned null");
        return new VistierieRunDetail(
            body.path("run_id").asText(), agentName, body.path("status").asText(),
            null, null, null, null
        );
    }

    @Override
    public List<VistierieRunEvent> getRunEvents(String runId) {
        try {
            var body = tenantClient.get().uri("/runs/{id}/events", runId).retrieve().body(JsonNode.class);
            if (body == null || !body.isArray()) return List.of();
            var result = new ArrayList<VistierieRunEvent>();
            for (var node : body) {
                result.add(new VistierieRunEvent(
                    node.path("id").asLong(),
                    node.path("ts").asText(),
                    node.path("level").asText(),
                    node.path("type").asText(),
                    null
                ));
            }
            return result;
        } catch (Exception e) { return List.of(); }
    }

    @Override
    public BudgetStatus getTenantBudget() {
        try {
            var body = adminClient.get().uri("/admin/tenants/dracul/budget").retrieve().body(JsonNode.class);
            return parseBudgetStatus(body);
        } catch (Exception e) { return BudgetStatus.empty(); }
    }

    @Override
    public BudgetStatus patchTenantBudget(BudgetPatch patch) {
        var body = adminClient.patch().uri("/admin/tenants/dracul/budget")
            .body(buildPatchBody(patch))
            .retrieve().body(JsonNode.class);
        return parseBudgetStatus(body);
    }

    @Override
    public BudgetStatus getAgentBudget(String agentName) {
        try {
            var body = adminClient.get()
                .uri("/admin/tenants/dracul/agents/{agent}/budget", agentName)
                .retrieve().body(JsonNode.class);
            return parseBudgetStatus(body);
        } catch (Exception e) { return BudgetStatus.empty(); }
    }

    @Override
    public BudgetStatus patchAgentBudget(String agentName, BudgetPatch patch) {
        var body = adminClient.patch()
            .uri("/admin/tenants/dracul/agents/{agent}/budget", agentName)
            .body(buildPatchBody(patch))
            .retrieve().body(JsonNode.class);
        return parseBudgetStatus(body);
    }

    @Override
    public KillStatus getKillStatus() {
        try {
            var body = adminClient.get().uri("/admin/tenants/dracul/kill").retrieve().body(JsonNode.class);
            if (body == null) return new KillStatus(null, null, null);
            return new KillStatus(
                body.path("until").isNull() ? null : body.path("until").asText(),
                body.path("reason").isNull() ? null : body.path("reason").asText(),
                body.path("setBy").isNull() ? null : body.path("setBy").asText()
            );
        } catch (Exception e) { return new KillStatus(null, null, null); }
    }

    @Override
    public void setKill(String reason) {
        adminClient.post().uri("/admin/tenants/dracul/kill")
            .body(java.util.Map.of("reason", reason))
            .retrieve().toBodilessEntity();
    }

    @Override
    public void clearKill() {
        adminClient.delete().uri("/admin/tenants/dracul/kill").retrieve().toBodilessEntity();
    }

    private BudgetStatus parseBudgetStatus(JsonNode body) {
        if (body == null) return BudgetStatus.empty();
        return new BudgetStatus(
            body.path("daily_cap_micros").isNull()     ? null : body.path("daily_cap_micros").longValue(),
            body.path("monthly_cap_micros").isNull()   ? null : body.path("monthly_cap_micros").longValue(),
            body.path("daily_warn_percent").isNull()   ? null : body.path("daily_warn_percent").intValue(),
            body.path("monthly_warn_percent").isNull() ? null : body.path("monthly_warn_percent").intValue(),
            body.path("daily_usage_micros").asLong(0),
            body.path("monthly_usage_micros").asLong(0),
            body.path("daily_warned").asBoolean(false),
            body.path("monthly_warned").asBoolean(false),
            body.path("daily_blocked").asBoolean(false),
            body.path("monthly_blocked").asBoolean(false)
        );
    }

    private java.util.Map<String, Object> buildPatchBody(BudgetPatch patch) {
        var map = new java.util.HashMap<String, Object>();
        if (patch.dailyCapMicros()     != null) map.put("daily_cap_micros",     patch.dailyCapMicros());
        if (patch.monthlyCapMicros()   != null) map.put("monthly_cap_micros",   patch.monthlyCapMicros());
        if (patch.dailyWarnPercent()   != null) map.put("daily_warn_percent",   patch.dailyWarnPercent());
        if (patch.monthlyWarnPercent() != null) map.put("monthly_warn_percent", patch.monthlyWarnPercent());
        return map;
    }

    @Override
    public Optional<AgentDetail> getAgent(String name) {
        try {
            AgentDetail body = tenantClient.get()
                    .uri("/agents/{name}", name)
                    .retrieve()
                    .body(AgentDetail.class);
            return Optional.ofNullable(body);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public AgentDetail registerAgent(CreateAgentRequest req) {
        AgentDetail body = tenantClient.post()
                .uri("/agents")
                .body(req)
                .retrieve()
                .body(AgentDetail.class);
        if (body == null) throw new RuntimeException("registerAgent returned null");
        return body;
    }

    @Override
    public AgentDetail updateAgent(String name, UpdateAgentRequest req) {
        AgentDetail body = tenantClient.put()
                .uri("/agents/{name}", name)
                .body(req)
                .retrieve()
                .body(AgentDetail.class);
        if (body == null) throw new RuntimeException("updateAgent returned null");
        return body;
    }
}
