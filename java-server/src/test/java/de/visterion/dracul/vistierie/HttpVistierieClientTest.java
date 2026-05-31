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

    RestClient tenantClient;
    RestClient adminClient;

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
        tenantClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(wm.baseUrl())
                .defaultHeader("Authorization", "Bearer tenant-tkn")
                .defaultHeader("X-Tenant-Id", "dracul")
                .build();
        adminClient = RestClient.builder()
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
                          {"name":"spinoff","paused":false,"last_tick_at":"2026-05-23T01:00:00Z"},
                          {"name":"insider","paused":true,"last_tick_at":null}
                        ]
                        """)));

        var result = client.listStrigoi();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("spinoff");
        assertThat(result.get(0).state()).isEqualTo("resting");
        assertThat(result.get(0).lastRunAt()).isEqualTo("2026-05-23T01:00:00Z");
        assertThat(result.get(1).name()).isEqualTo("insider");
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
                  "hunts_this_month":3,"scheduled_hunts_this_month":4,
                  "avg_prey_per_hunt":1.5,"hit_rate_90d":0.7,
                  "configuration":{
                    "cron":"0 1 * * *","next_run_at":"2026-05-24T01:00:00Z",
                    "disabled":false,"tier":"Standard",
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
    void getStrigoiDetail_unknownAgentReturnsEmpty() {
        wm.stubFor(get(urlEqualTo("/agents/ghost"))
                .willReturn(aResponse().withStatus(404)));

        assertThat(client.getStrigoiDetail("ghost")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // getTodayCostUsd
    // -------------------------------------------------------------------------

    @Test
    void getTodayCostUsd_sumsCostEurFromArray() {
        wm.stubFor(get(urlPathEqualTo("/admin/cost"))
                .withQueryParam("granularity", equalTo("day"))
                .withQueryParam("group_by", equalTo("tenant"))
                .willReturn(okJson("""
                        [{"cost_eur":1.5},{"cost_eur":0.75}]
                        """)));

        assertThat(client.getTodayCostUsd()).isEqualTo(2.25);
    }

    @Test
    void getTodayCostUsd_singleObjectShape() {
        wm.stubFor(get(urlPathEqualTo("/admin/cost"))
                .withQueryParam("granularity", equalTo("day"))
                .willReturn(okJson("""
                        {"cost_eur":3.0}
                        """)));

        assertThat(client.getTodayCostUsd()).isEqualTo(3.0);
    }

    @Test
    void getTodayCostUsd_returnsZeroOn500() {
        wm.stubFor(get(urlPathEqualTo("/admin/cost"))
                .willReturn(aResponse().withStatus(500)));

        assertThat(client.getTodayCostUsd()).isEqualTo(0.0);
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
                              "bucket": "%s",
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
}
