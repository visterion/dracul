package de.visterion.dracul.vistierie;

import tools.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class HttpVistierieClientTest {

    static WireMockServer wm;
    HttpVistierieClient client;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        // Force HTTP/1.1: WireMock does not support HTTP/2, but JDK's HttpClient
        // defaults to HTTP/2 with prior-knowledge upgrade, causing RST_STREAM on
        // request-body methods (POST/PATCH). Explicitly downgrade to HTTP_1_1.
        var jdkClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        var factory = new JdkClientHttpRequestFactory(jdkClient);
        RestClient tenantClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(wm.baseUrl())
                .defaultHeader("Authorization", "Bearer tenant-tkn")
                .defaultHeader("X-Tenant-Id", "dracul")
                .build();
        RestClient adminClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(wm.baseUrl())
                .defaultHeader("Authorization", "Bearer admin-tkn")
                .build();
        client = new HttpVistierieClient(tenantClient, adminClient, new ObjectMapper());
    }

    // -------------------------------------------------------------------------
    // listStrigoi
    // -------------------------------------------------------------------------

    @Test
    void listStrigoi_returnsParsedList() {
        wm.stubFor(get(urlEqualTo("/agents"))
                .willReturn(okJson("""
                        [
                          {"id":"a1","name":"spinoff","version":1,"paused":false,"updated_at":"2026-05-23T01:00:00Z"},
                          {"id":"a2","name":"insider","version":2,"paused":true,"updated_at":null}
                        ]
                        """)));

        var result = client.listStrigoi();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("spinoff");
        assertThat(result.get(0).state()).isEqualTo("resting");
        assertThat(result.get(0).lastRunAt()).isEqualTo("2026-05-23T01:00:00Z");
        assertThat(result.get(1).state()).isEqualTo("paused");
        assertThat(result.get(1).lastRunAt()).isNull();
    }

    @Test
    void listStrigoi_emptyArrayReturnsEmptyList() {
        wm.stubFor(get(urlEqualTo("/agents")).willReturn(okJson("[]")));
        assertThat(client.listStrigoi()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // getStrigoiDetail
    // -------------------------------------------------------------------------

    @Test
    void getStrigoiDetail_returnsPopulatedOptional() {
        wm.stubFor(get(urlEqualTo("/agents/spinoff")).willReturn(okJson("""
                {
                  "name":"spinoff","anomaly_type":"SPINOFF","description":"Spin-off hunter",
                  "reference":"ref","paused":false,"last_tick_at":"2026-05-23T01:00:00Z",
                  "schedule":"0 1 * * *","next_run_at":"2026-05-24T01:00:00Z",
                  "hunts_this_month":3,"scheduled_hunts_this_month":4,
                  "avg_prey_per_hunt":1.5,"hit_rate_90d":0.7,
                  "configuration":{
                    "tier":"Standard",
                    "daily_budget_usd":5.0,"daily_used_usd":1.2,
                    "monthly_budget_usd":50.0,"monthly_used_usd":10.0,
                    "primary_provider":"anthropic","fallback_provider":null
                  }
                }
                """)));
        wm.stubFor(get(urlEqualTo("/admin/runs?agent=spinoff&limit=5")).willReturn(okJson("""
                [
                  {"id":"run-1","started_at":"2026-05-23T01:05:00Z","prey_count":2,"cost_usd":0.05,"model":"claude-3-5-sonnet"}
                ]
                """)));

        Optional<de.visterion.dracul.strigoi.StrigoiDetail> result = client.getStrigoiDetail("spinoff");

        assertThat(result).isPresent();
        var detail = result.get();
        assertThat(detail.name()).isEqualTo("spinoff");
        assertThat(detail.anomalyType()).isEqualTo("SPINOFF");
        assertThat(detail.huntsThisMonth()).isEqualTo(3);
        assertThat(detail.hitRate90d()).isEqualTo(0.7);
        assertThat(detail.recentRuns()).hasSize(1);
        assertThat(detail.recentRuns().get(0).id()).isEqualTo("run-1");
        assertThat(detail.recentRuns().get(0).preyCount()).isEqualTo(2);
        assertThat(detail.configuration().cron()).isEqualTo("0 1 * * *");
        assertThat(detail.configuration().primaryProvider()).isEqualTo("anthropic");
        assertThat(detail.configuration().fallbackProvider()).isNull();
    }

    @Test
    void getStrigoiDetail_readsFlatScheduleFields() {
        wm.stubFor(get(urlEqualTo("/agents/strigoi-spin")).willReturn(okJson("""
                {
                  "name":"strigoi-spin","anomaly_type":"SPINOFF","description":"Spin-off hunter",
                  "reference":"ref","paused":true,"last_tick_at":"2026-06-07T04:00:00Z",
                  "schedule":"0 0 4 * * 1-5","next_run_at":"2026-06-11T04:00:00Z",
                  "hunts_this_month":1,"scheduled_hunts_this_month":2,
                  "avg_prey_per_hunt":0.5,"hit_rate_90d":0.4
                }
                """)));
        wm.stubFor(get(urlEqualTo("/admin/runs?agent=strigoi-spin&limit=5")).willReturn(okJson("[]")));

        var d = client.getStrigoiDetail("strigoi-spin").orElseThrow();

        assertThat(d.configuration().cron()).isEqualTo("0 0 4 * * 1-5");
        assertThat(d.configuration().nextRunAt()).isEqualTo("2026-06-11T04:00:00Z");
        assertThat(d.configuration().disabled()).isTrue();
    }

    @Test
    void getStrigoiDetail_unknownAgentReturnsEmpty() {
        wm.stubFor(get(urlEqualTo("/agents/ghost"))
                .willReturn(aResponse().withStatus(404)));

        assertThat(client.getStrigoiDetail("ghost")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // getTodayCostUsd
    // -------------------------------------------------------------------------

    @Test
    void getTodayCostUsd_sumsMultipleGroupsAcrossBuckets() {
        wm.stubFor(get(urlPathEqualTo("/admin/cost"))
                .withQueryParam("granularity", equalTo("day"))
                .withQueryParam("tenant", equalTo("dracul"))
                .willReturn(okJson("""
                        {"granularity":"day","buckets":[
                          {"ts":"2026-06-01T00:00:00Z","groups":[
                            {"cost_micros":1500000},{"cost_micros":750000}]}]}
                        """)));

        assertThat(client.getTodayCostUsd()).isEqualTo(2.25);
    }

    @Test
    void getTodayCostUsd_sumsSingleGroup() {
        wm.stubFor(get(urlPathEqualTo("/admin/cost"))
                .withQueryParam("granularity", equalTo("day"))
                .willReturn(okJson("""
                        {"granularity":"day","buckets":[
                          {"ts":"2026-06-01T00:00:00Z","groups":[
                            {"cost_micros":3000000}]}]}
                        """)));

        assertThat(client.getTodayCostUsd()).isEqualTo(3.0);
    }

    @Test
    void getTodayCostUsd_returnsZeroOn500() {
        wm.stubFor(get(urlPathEqualTo("/admin/cost"))
                .willReturn(aResponse().withStatus(500)));

        assertThat(client.getTodayCostUsd()).isEqualTo(0.0);
    }

    @Test
    void getTodayCostUsd_sumsGroupCostMicros() {
        wm.stubFor(get(urlPathEqualTo("/admin/cost")).willReturn(okJson("""
                {"from":"2026-06-01T00:00:00Z","to":"2026-06-01T12:00:00Z",
                 "granularity":"day","group_by":["tenant"],
                 "buckets":[{"ts":"2026-06-01T00:00:00Z","groups":[
                   {"dimensions":{"tenant":"dracul"},"calls":3,"input_tokens":100,
                    "output_tokens":50,"cache_creation_input_tokens":0,
                    "cache_read_input_tokens":0,"cost_micros":1500000,"cost_eur":1.5}]}]}
                """)));

        var total = client.getTodayCostUsd();

        assertThat(total).isEqualTo(1.5);
    }

    // -------------------------------------------------------------------------
    // getProviders
    // -------------------------------------------------------------------------

    @Test
    void getProviders_deduplicatesAndCapitalizesProviders() {
        wm.stubFor(get(urlEqualTo("/admin/routing-rules")).willReturn(okJson("""
                [
                  {"tier":"Standard","provider":"anthropic","model":"claude-3-5-sonnet"},
                  {"tier":"Premium","provider":"anthropic","model":"claude-opus"},
                  {"tier":"Standard","provider":"openai","model":"gpt-4o"}
                ]
                """)));

        var result = client.getProviders();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo("anthropic");
        assertThat(result.get(0).name()).isEqualTo("Anthropic");
        assertThat(result.get(0).status()).isEqualTo("connected");
        assertThat(result.get(1).id()).isEqualTo("openai");
        assertThat(result.get(1).name()).isEqualTo("Openai");
    }

    @Test
    void getProviders_returnsEmptyListOnError() {
        wm.stubFor(get(urlEqualTo("/admin/routing-rules"))
                .willReturn(aResponse().withStatus(503)));

        assertThat(client.getProviders()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // getDashboardData
    // -------------------------------------------------------------------------

    @Test
    void getDashboardData_parsesBucketsAndReturns30Days() {
        String todayBucket = java.time.LocalDate.now().toString() + "T00:00:00Z";
        wm.stubFor(get(urlPathEqualTo("/admin/cost"))
                .withQueryParam("granularity", equalTo("day"))
                .withQueryParam("tenant", equalTo("dracul"))
                .willReturn(okJson("""
                        {
                          "buckets": [
                            {
                              "ts": "%s",
                              "groups": [
                                {"cost_micros": 2000000},
                                {"cost_micros": 500000}
                              ]
                            }
                          ]
                        }
                        """.formatted(todayBucket))));

        var result = client.getDashboardData();

        assertThat(result).hasSize(30);
        // Find today's entry
        var today = java.time.LocalDate.now().toString();
        var todayEntry = result.stream().filter(d -> d.date().equals(today)).findFirst();
        assertThat(todayEntry).isPresent();
        assertThat(todayEntry.get().totalUsd()).isEqualTo(2.5);
    }

    @Test
    void getDashboardData_mapsBucketTsToDate() {
        var today = java.time.LocalDate.now().toString();
        wm.stubFor(get(urlPathEqualTo("/admin/cost")).willReturn(okJson("""
                {"from":"%sT00:00:00Z","to":"%sT00:00:00Z","granularity":"day","group_by":[],
                 "buckets":[{"ts":"%sT00:00:00Z","groups":[
                   {"dimensions":{},"calls":1,"input_tokens":10,"output_tokens":5,
                    "cache_creation_input_tokens":0,"cache_read_input_tokens":0,
                    "cost_micros":2000000,"cost_eur":2.0}]}]}
                """.formatted(today, today, today))));

        var series = client.getDashboardData();

        assertThat(series).hasSize(30);
        var last = series.get(series.size() - 1);
        assertThat(last.date()).isEqualTo(today);
        assertThat(last.totalUsd()).isEqualTo(2.0);
    }

    @Test
    void getDashboardData_returnsZeroSpendOnError() {
        wm.stubFor(get(urlPathEqualTo("/admin/cost"))
                .withQueryParam("tenant", equalTo("dracul"))
                .willReturn(aResponse().withStatus(500)));

        var result = client.getDashboardData();

        assertThat(result).hasSize(30);
        assertThat(result.stream().mapToDouble(VistierieData.DailySpend::totalUsd).sum()).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // patchAgent
    // -------------------------------------------------------------------------

    @Test
    void patchAgent_sendsCorrectBody() {
        wm.stubFor(patch(urlEqualTo("/agents/spinoff"))
                .willReturn(aResponse().withStatus(200)));

        assertThatCode(() -> client.patchAgent("spinoff", true)).doesNotThrowAnyException();

        wm.verify(patchRequestedFor(urlEqualTo("/agents/spinoff")));
    }

    // -------------------------------------------------------------------------
    // listRuns
    // -------------------------------------------------------------------------

    @Test
    void listRuns_returnsParsedRunList() {
        wm.stubFor(get(urlEqualTo("/runs")).willReturn(okJson("""
                [
                  {
                    "run_id":"run-42","agent_name":"spinoff","status":"completed",
                    "started_at":"2026-05-23T01:00:00Z","finished_at":"2026-05-23T01:05:00Z",
                    "summary":"Found 2 prey","error":null
                  }
                ]
                """)));

        var result = client.listRuns();

        assertThat(result).hasSize(1);
        var run = result.get(0);
        assertThat(run.id()).isEqualTo("run-42");
        assertThat(run.agentName()).isEqualTo("spinoff");
        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.startedAt()).isEqualTo("2026-05-23T01:00:00Z");
        assertThat(run.finishedAt()).isEqualTo("2026-05-23T01:05:00Z");
        assertThat(run.summary()).isEqualTo("Found 2 prey");
        assertThat(run.error()).isNull();
    }

    @Test
    void listRuns_emptyArrayReturnsEmptyList() {
        wm.stubFor(get(urlEqualTo("/runs")).willReturn(okJson("[]")));
        assertThat(client.listRuns()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // triggerRun
    // -------------------------------------------------------------------------

    @Test
    void triggerRun_returnsRunDetail() {
        wm.stubFor(post(urlEqualTo("/agents/spinoff/run"))
                .willReturn(okJson("""
                        {"run_id":"run-99","status":"running"}
                        """)));

        var result = client.triggerRun("spinoff");

        assertThat(result.id()).isEqualTo("run-99");
        assertThat(result.agentName()).isEqualTo("spinoff");
        assertThat(result.status()).isEqualTo("running");
        assertThat(result.startedAt()).isNull();
    }

    @Test
    void triggerRun_withCompletionWebhook_sendsWebhookAndTokenInBody() {
        wm.stubFor(post(urlEqualTo("/agents/renfield/run"))
                .willReturn(okJson("""
                        {"run_id":"run-77","status":"queued"}
                        """)));

        var result = client.triggerRun("renfield", java.util.Map.of("k", "v"),
                "http://localhost:8080/api/renfield/complete", "tok-1");

        assertThat(result.id()).isEqualTo("run-77");
        wm.verify(postRequestedFor(urlEqualTo("/agents/renfield/run"))
                .withRequestBody(matchingJsonPath("$.payload.k", equalTo("v")))
                .withRequestBody(matchingJsonPath("$.completion_webhook",
                        equalTo("http://localhost:8080/api/renfield/complete")))
                .withRequestBody(matchingJsonPath("$.completion_webhook_token", equalTo("tok-1"))));
    }

    @Test
    void triggerRun_withoutWebhook_omitsWebhookFields() {
        wm.stubFor(post(urlEqualTo("/agents/spinoff/run"))
                .willReturn(okJson("""
                        {"run_id":"run-78","status":"queued"}
                        """)));

        client.triggerRun("spinoff", java.util.Map.of("k", "v"));

        wm.verify(postRequestedFor(urlEqualTo("/agents/spinoff/run"))
                .withRequestBody(notMatching(".*completion_webhook.*")));
    }

    // -------------------------------------------------------------------------
    // getRunEvents
    // -------------------------------------------------------------------------

    @Test
    void getRunEvents_returnsParsedEvents() {
        wm.stubFor(get(urlEqualTo("/runs/run-42/events")).willReturn(okJson("""
                [
                  {"id":1,"ts":"2026-05-23T01:00:01Z","level":"INFO","type":"START"},
                  {"id":2,"ts":"2026-05-23T01:05:00Z","level":"INFO","type":"DONE"}
                ]
                """)));

        var result = client.getRunEvents("run-42");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(0).type()).isEqualTo("START");
        assertThat(result.get(1).level()).isEqualTo("INFO");
    }

    @Test
    void getRunEvents_emptyArrayReturnsEmptyList() {
        wm.stubFor(get(urlEqualTo("/runs/no-such-run/events")).willReturn(okJson("[]")));
        assertThat(client.getRunEvents("no-such-run")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // getTenantBudget
    // -------------------------------------------------------------------------

    @Test
    void getTenantBudget_returnsParsedBudgetStatus() {
        wm.stubFor(get(urlEqualTo("/admin/tenants/dracul/budget")).willReturn(okJson("""
                {
                  "daily_cap_micros":5000000,"monthly_cap_micros":100000000,
                  "daily_warn_percent":80,"monthly_warn_percent":90,
                  "daily_usage_micros":1234567,"monthly_usage_micros":9876543,
                  "daily_warned":false,"monthly_warned":false,
                  "daily_blocked":false,"monthly_blocked":false
                }
                """)));

        var result = client.getTenantBudget();

        assertThat(result.dailyCapMicros()).isEqualTo(5_000_000L);
        assertThat(result.monthlyCapMicros()).isEqualTo(100_000_000L);
        assertThat(result.dailyWarnPercent()).isEqualTo(80);
        assertThat(result.dailyUsageMicros()).isEqualTo(1_234_567L);
        assertThat(result.dailyBlocked()).isFalse();
    }

    @Test
    void getTenantBudget_returnsEmptyOnError() {
        wm.stubFor(get(urlEqualTo("/admin/tenants/dracul/budget"))
                .willReturn(aResponse().withStatus(500)));

        var result = client.getTenantBudget();
        assertThat(result).isEqualTo(BudgetStatus.empty());
    }

    // -------------------------------------------------------------------------
    // patchTenantBudget
    // -------------------------------------------------------------------------

    @Test
    void patchTenantBudget_sendsAndReturnsUpdatedBudget() {
        wm.stubFor(patch(urlEqualTo("/admin/tenants/dracul/budget"))
                .willReturn(okJson("""
                        {
                          "daily_cap_micros":10000000,"monthly_cap_micros":200000000,
                          "daily_warn_percent":75,"monthly_warn_percent":85,
                          "daily_usage_micros":0,"monthly_usage_micros":0,
                          "daily_warned":false,"monthly_warned":false,
                          "daily_blocked":false,"monthly_blocked":false
                        }
                        """)));

        var patch = new BudgetPatch(10_000_000L, 200_000_000L, 75, 85);
        var result = client.patchTenantBudget(patch);

        assertThat(result.dailyCapMicros()).isEqualTo(10_000_000L);
        assertThat(result.dailyWarnPercent()).isEqualTo(75);
        wm.verify(patchRequestedFor(urlEqualTo("/admin/tenants/dracul/budget")));
    }

    // -------------------------------------------------------------------------
    // getAgentBudget
    // -------------------------------------------------------------------------

    @Test
    void getAgentBudget_returnsParsedBudget() {
        wm.stubFor(get(urlEqualTo("/admin/tenants/dracul/agents/spinoff/budget"))
                .willReturn(okJson("""
                        {
                          "daily_cap_micros":2000000,"monthly_cap_micros":50000000,
                          "daily_warn_percent":70,"monthly_warn_percent":80,
                          "daily_usage_micros":500000,"monthly_usage_micros":3000000,
                          "daily_warned":false,"monthly_warned":false,
                          "daily_blocked":false,"monthly_blocked":false
                        }
                        """)));

        var result = client.getAgentBudget("spinoff");

        assertThat(result.dailyCapMicros()).isEqualTo(2_000_000L);
        assertThat(result.dailyUsageMicros()).isEqualTo(500_000L);
    }

    @Test
    void getAgentBudget_returnsEmptyOnError() {
        wm.stubFor(get(urlEqualTo("/admin/tenants/dracul/agents/ghost/budget"))
                .willReturn(aResponse().withStatus(404)));

        assertThat(client.getAgentBudget("ghost")).isEqualTo(BudgetStatus.empty());
    }

    // -------------------------------------------------------------------------
    // patchAgentBudget
    // -------------------------------------------------------------------------

    @Test
    void patchAgentBudget_sendsAndReturnsUpdatedBudget() {
        wm.stubFor(patch(urlEqualTo("/admin/tenants/dracul/agents/spinoff/budget"))
                .willReturn(okJson("""
                        {
                          "daily_cap_micros":3000000,"monthly_cap_micros":60000000,
                          "daily_warn_percent":null,"monthly_warn_percent":null,
                          "daily_usage_micros":0,"monthly_usage_micros":0,
                          "daily_warned":false,"monthly_warned":false,
                          "daily_blocked":false,"monthly_blocked":false
                        }
                        """)));

        var patch = new BudgetPatch(3_000_000L, 60_000_000L, null, null);
        var result = client.patchAgentBudget("spinoff", patch);

        assertThat(result.dailyCapMicros()).isEqualTo(3_000_000L);
        assertThat(result.dailyWarnPercent()).isNull();
        wm.verify(patchRequestedFor(urlEqualTo("/admin/tenants/dracul/agents/spinoff/budget")));
    }

    // -------------------------------------------------------------------------
    // getKillStatus
    // -------------------------------------------------------------------------

    @Test
    void getKillStatus_activeKill() {
        wm.stubFor(get(urlEqualTo("/admin/tenants/dracul/kill")).willReturn(okJson("""
                {"until":"2026-05-24T23:59:59Z","reason":"circuit-breaker","setBy":"ops"}
                """)));

        var result = client.getKillStatus();

        assertThat(result.active()).isTrue();
        assertThat(result.until()).isEqualTo("2026-05-24T23:59:59Z");
        assertThat(result.reason()).isEqualTo("circuit-breaker");
        assertThat(result.setBy()).isEqualTo("ops");
    }

    @Test
    void getKillStatus_nullFieldsWhenNotActive() {
        wm.stubFor(get(urlEqualTo("/admin/tenants/dracul/kill")).willReturn(okJson("""
                {"until":null,"reason":null,"setBy":null}
                """)));

        var result = client.getKillStatus();

        assertThat(result.active()).isFalse();
        assertThat(result.until()).isNull();
    }

    @Test
    void getKillStatus_returnsInactiveOnError() {
        wm.stubFor(get(urlEqualTo("/admin/tenants/dracul/kill"))
                .willReturn(aResponse().withStatus(500)));

        var result = client.getKillStatus();
        assertThat(result).isEqualTo(new KillStatus(null, null, null));
    }

    // -------------------------------------------------------------------------
    // setKill
    // -------------------------------------------------------------------------

    @Test
    void setKill_postsReasonBody() {
        wm.stubFor(post(urlEqualTo("/admin/tenants/dracul/kill"))
                .willReturn(aResponse().withStatus(200)));

        assertThatCode(() -> client.setKill("manual-override")).doesNotThrowAnyException();

        wm.verify(postRequestedFor(urlEqualTo("/admin/tenants/dracul/kill")));
    }

    // -------------------------------------------------------------------------
    // clearKill
    // -------------------------------------------------------------------------

    @Test
    void clearKill_sendsDelete() {
        wm.stubFor(delete(urlEqualTo("/admin/tenants/dracul/kill"))
                .willReturn(aResponse().withStatus(204)));

        assertThatCode(() -> client.clearKill()).doesNotThrowAnyException();

        wm.verify(deleteRequestedFor(urlEqualTo("/admin/tenants/dracul/kill")));
    }

    // -------------------------------------------------------------------------
    // Tenant header
    // -------------------------------------------------------------------------

    @Test
    void allRequests_sendTenantHeader() {
        wm.stubFor(get(urlEqualTo("/agents")).willReturn(okJson("[]")));

        client.listStrigoi();

        wm.verify(getRequestedFor(urlEqualTo("/agents"))
                .withHeader("X-Tenant-Id", equalTo("dracul")));
    }

    // -------------------------------------------------------------------------
    // Auth routing
    // -------------------------------------------------------------------------

    @Test
    void tenantEndpoint_sendsTenantBearer() {
        wm.stubFor(get(urlEqualTo("/agents")).willReturn(okJson("[]")));
        client.listStrigoi();
        wm.verify(getRequestedFor(urlEqualTo("/agents"))
                .withHeader("Authorization", equalTo("Bearer tenant-tkn")));
    }

    @Test
    void adminEndpoint_sendsAdminBearer() {
        wm.stubFor(get(urlPathEqualTo("/admin/routing-rules")).willReturn(okJson("[]")));
        client.getProviders();
        wm.verify(getRequestedFor(urlPathEqualTo("/admin/routing-rules"))
                .withHeader("Authorization", equalTo("Bearer admin-tkn")));
    }

    // -------------------------------------------------------------------------
    // Contract-pinning: snake_case field mapping
    // -------------------------------------------------------------------------

    @Test
    void listRuns_parsesSnakeCaseRunDetail() {
        wm.stubFor(get(urlEqualTo("/runs")).willReturn(okJson("""
                [{"run_id":"r1","agent_name":"strigoi-insider","agent_version":1,
                  "trigger":"manual","status":"done","started_at":"2026-06-01T04:00:00Z",
                  "finished_at":"2026-06-01T04:01:00Z","summary":"2 prey","output":null,
                  "error":null,"parent_run_id":null,"children_summary":{}}]
                """)));

        var runs = client.listRuns();

        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).id()).isEqualTo("r1");
        assertThat(runs.get(0).agentName()).isEqualTo("strigoi-insider");
        assertThat(runs.get(0).status()).isEqualTo("done");
        assertThat(runs.get(0).startedAt()).isEqualTo("2026-06-01T04:00:00Z");
    }

    @Test
    void triggerRun_parsesRunCreatedResponse() {
        wm.stubFor(post(urlEqualTo("/agents/strigoi-insider/run")).willReturn(okJson("""
                {"run_id":"r9","agent_name":"strigoi-insider","agent_version":1,"status":"queued"}
                """)));

        var run = client.triggerRun("strigoi-insider");

        assertThat(run.id()).isEqualTo("r9");
        assertThat(run.status()).isEqualTo("queued");
    }

    @Test
    void getKillStatus_parsesUntilReasonSetBy() {
        wm.stubFor(get(urlPathEqualTo("/admin/tenants/dracul/kill")).willReturn(okJson("""
                {"until":"2026-06-02T00:00:00Z","reason":"manual stop","setBy":"admin"}
                """)));

        var kill = client.getKillStatus();

        assertThat(kill.until()).isEqualTo("2026-06-02T00:00:00Z");
        assertThat(kill.reason()).isEqualTo("manual stop");
        assertThat(kill.setBy()).isEqualTo("admin");
    }

    // -------------------------------------------------------------------------
    // searchRuns / getRunTranscript / getRunToolCall
    // -------------------------------------------------------------------------

    @Test void searchRunsParsesHitsAndSendsTenantHeaders() {
        wm.stubFor(get(urlPathEqualTo("/runs/search"))
                .willReturn(okJson("""
                    {"items":[{"runId":"R1","agent":"strigoi-spin","status":"failed",
                      "hasError":true,"startedAt":"2026-06-16T00:00:00Z","rank":0.5,
                      "snippet":"tool_error 503"}],"limit":20,"offset":0}""")));
        var hits = client.searchRuns("strigoi-spin", "tool_error", Boolean.TRUE, null, null, null, 20, 0);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).runId()).isEqualTo("R1");
        assertThat(hits.get(0).hasError()).isTrue();
        assertThat(hits.get(0).snippet()).contains("503");
        wm.verify(getRequestedFor(urlPathEqualTo("/runs/search"))
                .withHeader("X-Tenant-Id", equalTo("dracul"))
                .withHeader("Authorization", equalTo("Bearer tenant-tkn")));
    }

    @Test void getRunTranscriptReturnsJson() {
        wm.stubFor(get(urlPathEqualTo("/runs/R1/transcript"))
                .willReturn(okJson("{\"run_id\":\"R1\",\"turn_count\":2,\"turns\":[]}")));
        var t = client.getRunTranscript("R1", "compact");
        assertThat(t.path("turn_count").asInt()).isEqualTo(2);
        wm.verify(getRequestedFor(urlPathEqualTo("/runs/R1/transcript"))
                .withQueryParam("view", equalTo("compact")));
    }

    @Test void getRunToolCallReturnsJson() {
        wm.stubFor(get(urlPathEqualTo("/runs/R1/tool-calls/toolu_1"))
                .willReturn(okJson("{\"tool_use_id\":\"toolu_1\",\"name\":\"finnhub\",\"is_error\":false}")));
        var tc = client.getRunToolCall("R1", "toolu_1");
        assertThat(tc.path("name").asText()).isEqualTo("finnhub");
    }

    @Test void searchRunsReturnsEmptyOnError() {
        wm.stubFor(get(urlPathEqualTo("/runs/search")).willReturn(aResponse().withStatus(503)));
        assertThat(client.searchRuns(null, "x", null, null, null, null, 20, 0)).isEmpty();
    }

    @Test void getRunTranscriptReturnsNullOnError() {
        wm.stubFor(get(urlPathEqualTo("/runs/Rx/transcript")).willReturn(aResponse().withStatus(404)));
        assertThat(client.getRunTranscript("Rx", "digest")).isNull();
    }

    // -------------------------------------------------------------------------
    // getCostByAgent
    // -------------------------------------------------------------------------

    @Test
    void getCostByAgent_parsesRowsAndMapsNullGroupToUnattributed() {
        wm.stubFor(get(urlPathEqualTo("/admin/cost"))
                .withQueryParam("group_by", equalTo("agent"))
                .withQueryParam("tenant", equalTo("dracul"))
                .willReturn(okJson("""
                        {"buckets":[{"groups":[
                          {"dimensions":{"agent":"strigoi-spin"},"cost_micros":800000},
                          {"dimensions":{"agent":"voievod"},"cost_micros":240000},
                          {"dimensions":{"agent":"(unattributed)"},"cost_micros":20000}
                        ]}]}
                        """)));

        var result = client.getCostByAgent(java.time.Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(result)
                .containsEntry("strigoi-spin", 800_000L)
                .containsEntry("voievod", 240_000L)
                .containsEntry("(unattributed)", 20_000L); // Vistierie F2 liefert NULL-agent_id bereits als "(unattributed)"-Gruppe
    }

    @Test
    void getCostByAgent_propagates400ForOldVistierie() {
        wm.stubFor(get(urlPathEqualTo("/admin/cost"))
                .withQueryParam("group_by", equalTo("agent"))
                .willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> client.getCostByAgent(java.time.Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.BadRequest.class);
    }
}
