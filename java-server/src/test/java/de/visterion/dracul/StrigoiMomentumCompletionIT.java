package de.visterion.dracul;

import de.visterion.dracul.agent.PromptRegistry;
import de.visterion.dracul.executor.EntryContext;
import de.visterion.dracul.executor.ExecutorIndicators;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionFixtures;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignal;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.executor.ExitProfile;
import de.visterion.dracul.executor.MechanismBudget;
import de.visterion.dracul.executor.Sizing;
import de.visterion.dracul.executor.VetoConfig;
import de.visterion.dracul.executor.VetoResult;
import de.visterion.dracul.executor.VetoService;
import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.strigoi.momentum.MomentumRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/** Spec 2026-10-04 §9: the real completion endpoint over Postgres, driven by a stored snapshot;
 *  a code-built prey reaches the executor and passes a real VetoService. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.executor.enabled=true",
        "dracul.strigoi.momentum.enabled=true",
        "dracul.strigoi.momentum.webhook-token=test-momentum-token",
        "dracul.public-url=http://test.invalid:9090"})
class StrigoiMomentumCompletionIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired ExecutorPositionRepository positions;
    @Autowired ExecutorSignalRepository signals;
    @Autowired MomentumRepository momentum;
    @Autowired VetoService vetoService;
    @Autowired JdbcClient jdbc;
    @Autowired PromptRegistry registry;
    @MockitoBean ExecutorIndicators indicators;   // no Agora in the test: emitted signals carry no reference

    RestClient rest;
    String tag;
    YearMonth month;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build();
        when(indicators.levels(anyString(), anyInt(), anyInt()))
                .thenReturn(new ExecutorIndicators.Levels(false, null, null, null, null));
        long n = System.nanoTime();
        tag = "M" + (n % 1_000_000L);
        month = YearMonth.of(4000 + (int) (n % 4000), 1 + (int) ((n / 7) % 12));
    }

    private String sym(String letter) {
        return tag + letter;
    }

    /** offered = A..F (ranks 1..6), universe = A..F + G (unranked, missing). */
    private void snapshot(String runId, String health) {
        ObjectNode p = objectMapper.createObjectNode();
        p.putObject("llm");
        p.put("ranked_count", 6);
        ArrayNode offered = p.putArray("offered");
        ArrayNode rankedAll = p.putArray("ranked_all");
        ArrayNode universe = p.putArray("universe");
        String[] letters = {"A", "B", "C", "D", "E", "F"};
        for (int i = 0; i < letters.length; i++) {
            ObjectNode o = offered.addObject();
            o.put("rank", i + 1);
            o.put("symbol", sym(letters[i]));
            o.put("company_name", "Synthetic " + letters[i] + " Corp");
            o.put("momentum_12_1_pct", new BigDecimal("80.00").subtract(BigDecimal.valueOf(i)));
            o.put("return_1m_pct", new BigDecimal("3.10"));
            rankedAll.addObject().put("symbol", sym(letters[i])).put("rank", i + 1).put("momentum_pct", 80 - i);
            universe.add(sym(letters[i]));
        }
        universe.add(sym("G"));
        p.putObject("unranked").put(sym("G"), "missing");
        momentum.insertSnapshot(runId, null, month, true, health, p);
    }

    private long open(String symbol, ExitProfile profile, boolean filled, String rebalanceExitAt) {
        ExecutorPosition base = ExecutorPositionFixtures.withoutKillLevel(null, "depot-1", symbol,
                "BUY", new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("65"),
                new BigDecimal("65"), 1, null, List.of("x"), "sig-" + symbol, "agent", null, null,
                "OPEN", null, null, null, 0, null, null, null, null, null, null, null, null, null, 0,
                null, null, null, null, null, null, false, null, filled ? "2026-10-01T14:30:00Z" : null);
        return positions.insert(ExecutorPositionFixtures.withRebalanceExitAt(
                ExecutorPositionFixtures.withProfileFields(base, profile, null, null, null, false),
                rebalanceExitAt));
    }

    private void complete(String runId, String output) {
        rest.post().uri("/api/strigoi-momentum/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-momentum-token")
                .header("X-Vistierie-Run-Id", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.readTree("{\"status\":\"done\",\"output\":" + output + "}"))
                .retrieve().toBodilessEntity();
    }

    private List<String> preySymbols(String runId) {
        return jdbc.sql("SELECT symbol FROM prey WHERE run_id = :r AND anomaly_type = 'MOMENTUM_12_1' ORDER BY symbol")
                .param("r", runId).query(String.class).list();
    }

    @Test
    void rebalanceFlagsEntriesSignalsAndTheMonthMark() {
        String run = "run-mc-" + tag;
        snapshot(run, "healthy");
        long heldFinal = open(sym("A"), ExitProfile.MOMENTUM, true, "2026-09-30T22:40:00Z");
        open(sym("C"), ExitProfile.STANDARD, true, null);                 // held elsewhere
        long gone = open(sym("H"), ExitProfile.MOMENTUM, true, null);     // not in the universe
        long carried = open(sym("G"), ExitProfile.MOMENTUM, true, null);  // unranked

        complete(run, "{\"prey\": [], \"vetoes\": [{\"symbol\": \"" + sym("B") + "\", \"reason\": \"synthetic takeover pending\"}]}");

        assertThat(positions.findById(heldFinal).rebalanceExitAt()).isNull();
        assertThat(positions.findById(gone).rebalanceExitAt()).isNotNull();
        assertThat(positions.findById(carried).rebalanceExitAt()).isNull();
        assertThat(preySymbols(run)).containsExactly(sym("D"), sym("E"), sym("F"));
        assertThat(momentum.rebalanceCompleted(month)).isTrue();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM executor_signal s JOIN prey p ON p.id = s.prey_id
                WHERE p.run_id = :r AND s.mechanism = 'MOMENTUM_12_1'
                """).param("r", run).query(Integer.class).single()).isEqualTo(3);

        // duplicate delivery: idempotent flags, no new prey, the month stays marked
        complete(run, "{\"prey\": [], \"vetoes\": [{\"symbol\": \"" + sym("B") + "\", \"reason\": \"synthetic takeover pending\"}]}");
        assertThat(preySymbols(run)).containsExactly(sym("D"), sym("E"), sym("F"));
        assertThat(positions.findById(gone).rebalanceExitAt()).isNotNull();
        assertThat(positions.findById(heldFinal).rebalanceExitAt()).isNull();
    }

    @Test
    void anUnavailableSnapshotChangesNothing() {
        String run = "run-mu-" + tag;
        snapshot(run, "unavailable");
        long held = open(sym("H"), ExitProfile.MOMENTUM, true, null);

        complete(run, "{\"prey\": []}");

        assertThat(positions.findById(held).rebalanceExitAt()).isNull();
        assertThat(preySymbols(run)).isEmpty();
        assertThat(momentum.rebalanceCompleted(month)).isFalse();
    }

    @Test
    void noSnapshotChangesNothing() {
        String run = "run-mn-" + tag;
        long held = open(sym("H"), ExitProfile.MOMENTUM, true, null);

        complete(run, "{\"prey\": [{\"symbol\": \"" + sym("Z") + "\", \"companyName\": \"x\"}]}");

        assertThat(positions.findById(held).rebalanceExitAt()).isNull();
        assertThat(preySymbols(run)).isEmpty();
    }

    /** §5.3 (R1 M6): the code-built prey survives SCHEMA_INVALID / LOW_CONFIDENCE and the rest of
     *  the real catalog with the MOMENTUM skips. */
    @Test
    void aCodeBuiltPreyPassesTheRealVetoService() {
        String run = "run-mv-" + tag;
        snapshot(run, "healthy");
        complete(run, "{\"prey\": []}");
        String signalId = jdbc.sql("""
                SELECT s.signal_id FROM executor_signal s JOIN prey p ON p.id = s.prey_id
                WHERE p.run_id = :r AND s.symbol = :s
                """).param("r", run).param("s", sym("A")).query(String.class).single();
        ExecutorSignal signal = signals.findById(signalId);
        // ruling m9: the signal carries the registry hash of THIS prompt, not just any version
        assertThat(signal.agentVersion())
                .isEqualTo(registry.entry("strigoi-momentum").orElseThrow().bodyHash());
        assertThat(signal.mechanism()).isEqualTo("MOMENTUM_12_1");

        EntryContext ctx = new EntryContext(
                new AccountSnapshot(new BigDecimal("100000"), new BigDecimal("100000"), "USD"),
                new BigDecimal("100"), new BigDecimal("2"), null, new BigDecimal("1000000000"), null,
                "Technology", List.of(), List.of(), List.of(), 99, 0, new BigDecimal("4000"),
                new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), BigDecimal.ONE,
                List.of(), "USD", null, new BigDecimal("2"), Map.of(), new BigDecimal("2500"));
        Sizing sizing = new Sizing(new BigDecimal("25"), new BigDecimal("35"), new BigDecimal("875"),
                null, null, true, "profile MOMENTUM: emergency stop", new BigDecimal("25"), null,
                "PROFILE_NOTIONAL", null);
        VetoConfig cfg = new VetoConfig(0.40, 35, new BigDecimal("100000"), 0.15, 5,
                new BigDecimal("5"), 200, 5, 1.0, 10, 25, 0.0, 3.0, "USD",
                new MechanismBudget("MOMENTUM_12_1:0.28"));

        VetoService.Outcome out = vetoService.evaluate(signal, ctx, sizing, cfg, new BigDecimal("100"));

        assertThat(out.passed()).as("trace %s", out.results()).isTrue();
        assertThat(out.results()).filteredOn(r -> "profile".equals(r.skipped()))
                .extracting(VetoResult::check)
                .containsExactlyInAnyOrder("LOW_CONFIDENCE", "HEAT_LIMIT", "CONCENTRATION",
                        "CORRELATED", "CHASED_AWAY", "BELOW_ANCHOR", "PACE_LIMIT");
    }
}
