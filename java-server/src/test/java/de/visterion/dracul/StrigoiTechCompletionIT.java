package de.visterion.dracul;

import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionFixtures;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExitProfile;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.strigoi.tech.TechBookService;
import de.visterion.dracul.strigoi.tech.TechCandidateService;
import de.visterion.dracul.strigoi.tech.TechEligibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/** Spec 2026-10-03 §9 (P1 4b, P2): the real completion endpoint over Postgres. The book and
 *  eligibility are mocked so the capacity is deterministic in a reused container. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.executor.enabled=true",
        "dracul.strigoi.tech.enabled=true",
        "dracul.strigoi.tech.webhook-token=test-tech-token",
        "dracul.public-url=http://test.invalid:9090"})
class StrigoiTechCompletionIT {

    @LocalServerPort int port;
    @Autowired JsonMapper objectMapper;
    @Autowired ExecutorPositionRepository positions;
    @Autowired PreyRepository preyRepo;
    @MockitoBean TechBookService book;
    @MockitoBean TechCandidateService candidates;

    RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .messageConverters(c -> { c.clear(); c.add(new JacksonJsonHttpMessageConverter(objectMapper)); })
                .build();
        when(book.snapshot()).thenReturn(new TechBookService.Snapshot(true, List.of(), List.of(),
                2, Set.of(), Set.of(), Set.of()));   // capacity = min(12, 3 - 2) = 1
        when(candidates.eligibility(anyString(), any()))
                .thenReturn(new TechEligibility.Verdict(true, List.of(), List.of()));
    }

    private long open(String symbol, ExitProfile profile, String connection, String status) {
        ExecutorPosition base = ExecutorPositionFixtures.withoutKillLevel(null, connection, symbol,
                "BUY", new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("65"),
                new BigDecimal("65"), 1, null, List.of("x"), "sig-" + symbol, "strigoi-tech",
                null, null, status, null, null, null, 0, null, null, null, null, null, null, null,
                null, null, 0, null, null, null, null, null, null, false, null, null);
        return positions.insert(ExecutorPositionFixtures.withProfileFields(base, profile, null,
                null, null, false));
    }

    private void complete(String runId, String output) {
        rest.post().uri("/api/strigoi-tech/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-tech-token")
                .header("X-Vistierie-Run-Id", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.readTree("{\"status\":\"done\",\"output\":" + output + "}"))
                .retrieve().toBodilessEntity();
    }

    private static String unique(String prefix) {
        return prefix + (System.nanoTime() % 1_000_000L);
    }

    /** 4b (R2 Major 1): a full basket (prey: []) still flags a catastrophe. */
    @Test
    void emptyPreyStillFlagsACatastrophe() {
        String symbol = unique("TCAT");
        long id = open(symbol, ExitProfile.CONVICTION, "depot-1", "OPEN");

        complete("run-cat-" + symbol, """
                {"prey": [], "catastrophe_exits": [{"symbol": "%s", "reason": "synthetic restatement",
                 "evidence": ["synthetic headline"]}]}
                """.formatted(symbol));

        assertThat(positions.findById(id).catastropheReason()).startsWith("synthetic restatement");
        assertThat(positions.findById(id).catastropheFlaggedAt()).isNotNull();
    }

    /** 4b: a duplicate re-delivery (all prey already persisted) still processes catastrophe exits. */
    @Test
    void duplicateRedeliveryStillProcessesCatastropheExits() {
        String pick = unique("TDUP");
        String flagged = unique("TFLG");
        long id = open(flagged, ExitProfile.CONVICTION, "depot-1", "OPEN");
        String preyJson = """
                {"symbol": "%s", "companyName": "Synthetic Dup", "confidence": 0.8, "thesis": "t",
                 "kill_criteria": ["k"], "horizon": "12m"}
                """.formatted(pick);

        complete("run-dup-1-" + pick, "{\"prey\": [" + preyJson + "]}");
        complete("run-dup-1-" + pick, "{\"prey\": [" + preyJson + "], \"catastrophe_exits\": "
                + "[{\"symbol\": \"" + flagged + "\", \"reason\": \"synthetic delisting\", "
                + "\"evidence\": [\"synthetic wire\"]}]}");

        assertThat(positions.findById(id).catastropheReason()).startsWith("synthetic delisting");
    }

    /** P2: STANDARD, CLOSED and foreign-connection rows are never flagged. */
    @Test
    void catastropheOnStandardClosedOrForeignRowsIsRejected() {
        String std = unique("TSTD");
        String closed = unique("TCLS");
        String foreign = unique("TFOR");
        long stdId = open(std, ExitProfile.STANDARD, "depot-1", "OPEN");
        long closedId = open(closed, ExitProfile.CONVICTION, "depot-1", "CLOSED");
        long foreignId = open(foreign, ExitProfile.CONVICTION, "other-conn", "OPEN");

        complete("run-rej-" + std, """
                {"prey": [], "catastrophe_exits": [
                  {"symbol": "%s", "reason": "r", "evidence": ["e"]},
                  {"symbol": "%s", "reason": "r", "evidence": ["e"]},
                  {"symbol": "%s", "reason": "r", "evidence": ["e"]}]}
                """.formatted(std, closed, foreign));

        assertThat(positions.findById(stdId).catastropheReason()).isNull();
        assertThat(positions.findById(closedId).catastropheReason()).isNull();
        assertThat(positions.findById(foreignId).catastropheReason()).isNull();
    }

    /** P2: picks beyond capacity (1) are not persisted. */
    @Test
    void picksBeyondCapacityAreNotPersisted() {
        String a = unique("TPKA");
        String b = unique("TPKB");

        complete("run-cap-" + a, """
                {"prey": [
                  {"symbol": "%s", "companyName": "Synthetic A", "confidence": 0.8, "thesis": "t", "kill_criteria": ["k"]},
                  {"symbol": "%s", "companyName": "Synthetic B", "confidence": 0.8, "thesis": "t", "kill_criteria": ["k"]}]}
                """.formatted(a, b));

        var persisted = preyRepo.findByDiscoveredBy("strigoi-tech", "default").stream()
                .map(p -> p.symbol()).toList();
        assertThat(persisted).contains(a).doesNotContain(b);
    }
}
